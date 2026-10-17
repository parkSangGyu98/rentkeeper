package com.rentkeeper.auth.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import com.rentkeeper.TestcontainersConfiguration;
import com.rentkeeper.auth.rememberme.HashingRememberMeServices;
import com.rentkeeper.landlord.application.LandlordService;
import com.rentkeeper.landlord.domain.EmailAddress;
import com.rentkeeper.support.RecordingEmailSender;
import com.rentkeeper.support.RecordingEmailSenderConfiguration;
import com.rentkeeper.support.TestData;

import jakarta.servlet.http.Cookie;

/**
 * 비밀번호 바꾸기 (민감 작업 재인증): 현재 비밀번호 필수, 틀리면 잠금 횟수에 포함, 다른 기기 로그아웃·지금 기기 유지.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RecordingEmailSenderConfiguration.class})
class PasswordChangeTests {

	private static final String EMAIL = "kim@example.com";

	private static final String PASSWORD = "Haengbok-Villa-2026";

	private static final String NEW_PASSWORD = "Saeroun-Bimil-Beonho-77";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	LandlordService landlordService;

	@Autowired
	PasswordEncoder passwordEncoder;

	@Autowired
	RecordingEmailSender mails;

	long landlordId;

	@BeforeEach
	void setUp() {
		TestData.reset(jdbcTemplate);
		jdbcTemplate.update("delete from persistent_logins");
		mails.clear();
		landlordId = landlordService.registerLocal(EmailAddress.of(EMAIL), passwordEncoder.encode(PASSWORD), "김임대");
	}

	@Test
	void requiresLogin() throws Exception {
		mockMvc.perform(get("/app/settings")).andExpect(redirectedUrl("/login"));
		mockMvc.perform(post("/app/settings/password").with(csrf())).andExpect(redirectedUrl("/login"));
	}

	@Test
	void settingsPageShowsEmailAndVerificationStatus() throws Exception {
		Cookie session = login(PASSWORD);

		mockMvc.perform(get("/app/settings").cookie(session)).andExpect(status().isOk())
				.andExpect(content().string(containsString(EMAIL)))
				.andExpect(content().string(containsString("아직 인증 전이에요")))
				.andExpect(content().string(containsString("비밀번호 바꾸기")));
	}

	@Test
	void wrongCurrentPasswordIsRejectedAndCountedAsFailure() throws Exception {
		Cookie session = login(PASSWORD);

		change(session, "wrong-current", NEW_PASSWORD)
				.andExpect(content().string(containsString("지금 쓰는 비밀번호가 맞지 않아요")));

		assertThat(jdbcTemplate.queryForObject("select failed_count from login_failure where landlord_id = ?",
				Integer.class, landlordId)).isEqualTo(1);
		assertThat(passwordEncoder.matches(PASSWORD, currentHash())).isTrue();
	}

	@Test
	void fiveWrongAttemptsLockEvenWithCorrectCurrentPassword() throws Exception {
		Cookie session = login(PASSWORD);
		for (int i = 0; i < 5; i++) {
			change(session, "wrong-current-" + i, NEW_PASSWORD);
		}

		change(session, PASSWORD, NEW_PASSWORD).andExpect(content().string(containsString("15분 뒤에 다시 시도해 주세요")));
		assertThat(passwordEncoder.matches(PASSWORD, currentHash())).isTrue();
	}

	@Test
	void weakOrSameOrMismatchedNewPasswordIsRejected() throws Exception {
		Cookie session = login(PASSWORD);

		change(session, PASSWORD, "password1").andExpect(content().string(containsString("너무 흔한 비밀번호예요")));
		change(session, PASSWORD, PASSWORD).andExpect(content().string(containsString("다른 비밀번호로 정해 주세요")));
		mockMvc.perform(post("/app/settings/password").with(csrf()).cookie(session).param("currentPassword", PASSWORD)
				.param("newPassword", NEW_PASSWORD).param("newPasswordConfirm", NEW_PASSWORD + "x"))
				.andExpect(content().string(containsString("새 비밀번호가 서로 달라요")));
		assertThat(passwordEncoder.matches(PASSWORD, currentHash())).isTrue();
	}

	@Test
	void successKeepsThisDeviceAndLogsOutOthers() throws Exception {
		Cookie thisDevice = login(PASSWORD);
		Cookie otherDevice = login(PASSWORD);
		loginWithRememberMe();
		assertThat(countPersistentLogins()).isEqualTo(1);

		MvcResult result = change(thisDevice, PASSWORD, NEW_PASSWORD).andExpect(redirectedUrl("/app/settings"))
				.andExpect(flash().attribute("notice", containsString("다른 기기에서는 모두 로그아웃됐어요"))).andReturn();

		Cookie renewed = result.getResponse().getCookie("SESSION");
		Cookie thisDeviceNow = renewed != null ? renewed : thisDevice;
		mockMvc.perform(get("/app").cookie(thisDeviceNow)).andExpect(status().isOk());
		mockMvc.perform(get("/app").cookie(otherDevice)).andExpect(redirectedUrl("/login"));
		assertThat(countPersistentLogins()).isZero();
		Cookie clearedRememberMe = result.getResponse().getCookie(HashingRememberMeServices.COOKIE_NAME);
		assertThat(clearedRememberMe).isNotNull();
		assertThat(clearedRememberMe.getMaxAge()).isZero();

		assertThat(passwordEncoder.matches(NEW_PASSWORD, currentHash())).isTrue();
		assertThat(mails.sent()).as("미인증 주소에는 보안 안내를 보내지 않음").isEmpty();
	}

	@Test
	void verifiedAddressGetsSecurityNotice() throws Exception {
		jdbcTemplate.update("update landlord set email_verified_at = now() where id = ?", landlordId);
		Cookie session = login(PASSWORD);

		change(session, PASSWORD, NEW_PASSWORD).andExpect(redirectedUrl("/app/settings"));

		assertThat(mails.lastTo(EMAIL).subject()).contains("비밀번호가 변경됐어요");
	}

	private Cookie login(String password) throws Exception {
		MvcResult result = mockMvc
				.perform(post("/login").with(csrf()).param("email", EMAIL).param("password", password))
				.andExpect(redirectedUrl("/app")).andReturn();
		return result.getResponse().getCookie("SESSION");
	}

	private void loginWithRememberMe() throws Exception {
		mockMvc.perform(post("/login").with(csrf()).param("email", EMAIL).param("password", PASSWORD)
				.param(HashingRememberMeServices.PARAMETER, "on")).andExpect(redirectedUrl("/app"));
	}

	private ResultActions change(Cookie session, String current, String newPassword) throws Exception {
		return mockMvc
				.perform(post("/app/settings/password").with(csrf()).cookie(session).param("currentPassword", current)
						.param("newPassword", newPassword).param("newPasswordConfirm", newPassword));
	}

	private String currentHash() {
		return jdbcTemplate.queryForObject("select password_hash from landlord where id = ?", String.class, landlordId);
	}

	private Integer countPersistentLogins() {
		return jdbcTemplate.queryForObject("select count(*) from persistent_logins", Integer.class);
	}

}
