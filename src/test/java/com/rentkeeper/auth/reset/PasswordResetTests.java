package com.rentkeeper.auth.reset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.rentkeeper.TestcontainersConfiguration;
import com.rentkeeper.auth.lock.LoginLockService;
import com.rentkeeper.auth.rememberme.HashingRememberMeServices;
import com.rentkeeper.landlord.application.LandlordService;
import com.rentkeeper.landlord.domain.EmailAddress;
import com.rentkeeper.support.MutableClock;
import com.rentkeeper.support.MutableClockConfiguration;
import com.rentkeeper.support.RecordingEmailSender;
import com.rentkeeper.support.RecordingEmailSenderConfiguration;
import com.rentkeeper.support.TestData;

import jakarta.servlet.http.Cookie;

/**
 * 비밀번호 재설정 (AUTH-3): 가입 여부 비노출, 30분·1회용, 재설정 후 모든 세션·remember-me 종료, 잠금 해제, 이메일
 * 인증 처리, 한도.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RecordingEmailSenderConfiguration.class, MutableClockConfiguration.class})
@ExtendWith(OutputCaptureExtension.class)
class PasswordResetTests {

	private static final String EMAIL = "kim@example.com";

	private static final String OLD_PASSWORD = "Haengbok-Villa-2026";

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

	@Autowired
	MutableClock clock;

	@Autowired
	LoginLockService lockService;

	long landlordId;

	@BeforeEach
	void setUp() {
		clock.set(MutableClockConfiguration.START);
		TestData.reset(jdbcTemplate);
		jdbcTemplate.update("delete from persistent_logins");
		mails.clear();
		landlordId = landlordService.registerLocal(EmailAddress.of(EMAIL), passwordEncoder.encode(OLD_PASSWORD), "김임대");
	}

	@Test
	void responseIsSameForRegisteredUnknownAndMalformedEmail() throws Exception {
		String registered = requestReset("KIM@example.com").andExpect(status().isOk()).andReturn().getResponse()
				.getContentAsString();
		String unknown = requestReset("nobody@example.com").andReturn().getResponse().getContentAsString();
		String malformed = requestReset("not-an-email").andReturn().getResponse().getContentAsString();

		assertThat(registered).contains("가입되어 있다면 비밀번호 재설정 메일을 보냈어요").isEqualTo(unknown).isEqualTo(malformed);
		assertThat(mails.sent()).hasSize(1);
		assertThat(mails.lastTo(EMAIL).body()).contains("http://localhost:8080/password-reset/confirm?token=")
				.contains("30분");
	}

	@Test
	void openingLinkShowsFormWithoutConsumingToken() throws Exception {
		requestReset(EMAIL);
		String token = mails.lastTokenTo(EMAIL);

		mockMvc.perform(get("/password-reset/confirm").param("token", token)).andExpect(status().isOk())
				.andExpect(header().string("Referrer-Policy", "no-referrer"))
				.andExpect(content().string(containsString("새 비밀번호 정하기")));

		confirm(token, NEW_PASSWORD).andExpect(redirectedUrl("/login?reset"));
	}

	@Test
	void resetChangesPasswordOnceAndVerifiesEmail() throws Exception {
		requestReset(EMAIL);
		String token = mails.lastTokenTo(EMAIL);

		confirm(token, NEW_PASSWORD).andExpect(redirectedUrl("/login?reset"));

		login(OLD_PASSWORD).andExpect(redirectedUrl("/login?error"));
		login(NEW_PASSWORD).andExpect(redirectedUrl("/app")).andExpect(authenticated());
		assertThat(landlordService.findVerifiedEmail(landlordId)).as("메일 링크로 본인 확인 → 인증 처리").isPresent();
		assertThat(mails.lastTo(EMAIL).subject()).contains("비밀번호가 변경됐어요");

		confirm(token, "Another-Password-99").andExpect(content().string(containsString("링크가 만료됐거나 이미 사용됐어요")));
	}

	@Test
	void resetEndsAllSessionsAndRememberMeTokens() throws Exception {
		Cookie deviceA = login(OLD_PASSWORD).andReturn().getResponse().getCookie("SESSION");
		Cookie rememberB = mockMvc
				.perform(post("/login").with(csrf()).param("email", EMAIL).param("password", OLD_PASSWORD)
						.param(HashingRememberMeServices.PARAMETER, "on"))
				.andReturn().getResponse().getCookie(HashingRememberMeServices.COOKIE_NAME);
		assertThat(countPersistentLogins()).isEqualTo(1);

		requestReset(EMAIL);
		confirm(mails.lastTokenTo(EMAIL), NEW_PASSWORD).andExpect(redirectedUrl("/login?reset"));

		assertThat(jdbcTemplate.queryForObject("select count(*) from spring_session where principal_name = ?",
				Integer.class, String.valueOf(landlordId))).isZero();
		assertThat(countPersistentLogins()).isZero();
		mockMvc.perform(get("/app").cookie(deviceA)).andExpect(redirectedUrl("/login"));
		mockMvc.perform(get("/app").cookie(rememberB)).andExpect(redirectedUrl("/login"));
	}

	@Test
	void resetUnlocksAccount() throws Exception {
		for (int i = 0; i < 5; i++) {
			lockService.recordFailure(landlordId);
		}
		assertThat(lockService.isLocked(landlordId)).isTrue();

		requestReset(EMAIL);
		confirm(mails.lastTokenTo(EMAIL), NEW_PASSWORD);

		login(NEW_PASSWORD).andExpect(redirectedUrl("/app"));
	}

	@Test
	void linkExpiresAfterThirtyMinutes() throws Exception {
		requestReset(EMAIL);
		String token = mails.lastTokenTo(EMAIL);
		clock.advance(Duration.ofMinutes(30));

		mockMvc.perform(get("/password-reset/confirm").param("token", token))
				.andExpect(content().string(containsString("링크가 만료됐거나 이미 사용됐어요")));
		confirm(token, NEW_PASSWORD).andExpect(content().string(containsString("링크가 만료됐거나 이미 사용됐어요")));
	}

	@Test
	void weakOrMismatchedPasswordKeepsTokenUsable() throws Exception {
		requestReset(EMAIL);
		String token = mails.lastTokenTo(EMAIL);

		confirm(token, "password1").andExpect(content().string(containsString("너무 흔한 비밀번호예요")));
		mockMvc.perform(post("/password-reset/confirm").with(csrf()).param("token", token)
				.param("password", NEW_PASSWORD).param("passwordConfirm", NEW_PASSWORD + "x"))
				.andExpect(content().string(containsString("비밀번호가 서로 달라요")));

		confirm(token, NEW_PASSWORD).andExpect(redirectedUrl("/login?reset"));
	}

	@Test
	void emailLimitSilentlyStopsMailsButKeepsSameResponse() throws Exception {
		for (int i = 0; i < 4; i++) {
			requestReset(EMAIL).andExpect(status().isOk())
					.andExpect(content().string(containsString("가입되어 있다면 비밀번호 재설정 메일을 보냈어요")));
		}
		assertThat(mails.sentTo(EMAIL)).as("이메일당 1시간 3회").hasSize(3);
	}

	@Test
	void ipLimitReturns429() throws Exception {
		for (int i = 0; i < 10; i++) {
			requestReset("user" + i + "@example.com").andExpect(status().isOk());
		}
		requestReset("user10@example.com").andExpect(status().isTooManyRequests());
	}

	@Test
	void rawTokenIsNotLogged(CapturedOutput output) throws Exception {
		requestReset(EMAIL);
		String token = mails.lastTokenTo(EMAIL);
		mockMvc.perform(get("/password-reset/confirm").param("token", token));
		confirm(token, NEW_PASSWORD);

		assertThat(output.getAll()).doesNotContain(token);
	}

	private ResultActions requestReset(String email) throws Exception {
		return mockMvc.perform(post("/password-reset").with(csrf()).param("email", email));
	}

	private ResultActions confirm(String token, String password) throws Exception {
		return mockMvc.perform(post("/password-reset/confirm").with(csrf()).param("token", token)
				.param("password", password).param("passwordConfirm", password));
	}

	private ResultActions login(String password) throws Exception {
		MockHttpServletRequestBuilder request = post("/login").with(csrf()).param("email", EMAIL).param("password",
				password);
		return mockMvc.perform(request);
	}

	private Integer countPersistentLogins() {
		return jdbcTemplate.queryForObject("select count(*) from persistent_logins", Integer.class);
	}

}
