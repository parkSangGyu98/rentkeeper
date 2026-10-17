package com.rentkeeper.auth.emailchange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.rentkeeper.TestcontainersConfiguration;
import com.rentkeeper.landlord.application.LandlordService;
import com.rentkeeper.landlord.domain.EmailAddress;
import com.rentkeeper.support.MutableClock;
import com.rentkeeper.support.MutableClockConfiguration;
import com.rentkeeper.support.RecordingEmailSender;
import com.rentkeeper.support.RecordingEmailSenderConfiguration;
import com.rentkeeper.support.TestData;

import jakarta.servlet.http.Cookie;

/**
 * 이메일 바꾸기 (M1-b 결정 7): 재인증, 새 주소 확인 후 반영, 이전 주소 안내(인증된 경우만), 중복, 만료.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RecordingEmailSenderConfiguration.class, MutableClockConfiguration.class})
class EmailChangeTests {

	private static final String EMAIL = "kim@example.com";

	private static final String NEW_EMAIL = "kim.new@example.com";

	private static final String PASSWORD = "Haengbok-Villa-2026";

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

	long landlordId;

	Cookie session;

	@BeforeEach
	void setUp() throws Exception {
		clock.set(MutableClockConfiguration.START);
		TestData.reset(jdbcTemplate);
		mails.clear();
		landlordId = landlordService.registerLocal(EmailAddress.of(EMAIL), passwordEncoder.encode(PASSWORD), "김임대");
		session = mockMvc.perform(post("/login").with(csrf()).param("email", EMAIL).param("password", PASSWORD))
				.andExpect(redirectedUrl("/app")).andReturn().getResponse().getCookie("SESSION");
	}

	@Test
	void settingsPageShowsEmailChangeButton() throws Exception {
		mockMvc.perform(get("/app/settings").cookie(session)).andExpect(content().string(containsString("이메일 바꾸기")));
	}

	@Test
	void requestSendsLinkToNewAddressWithoutChangingYet() throws Exception {
		request(PASSWORD, "Kim.New@Example.com").andExpect(content().string(containsString("새 주소로 확인 메일을 보냈어요")));

		assertThat(mails.lastTo(NEW_EMAIL).body()).contains("http://localhost:8080/verify-email-change?token=");
		assertThat(currentEmail()).isEqualTo(EMAIL);
	}

	@Test
	void requestValidatesPasswordAndAddress() throws Exception {
		request("wrong-password", NEW_EMAIL).andExpect(content().string(containsString("지금 쓰는 비밀번호가 맞지 않아요")));
		request(PASSWORD, "not-an-email").andExpect(content().string(containsString("새 이메일 주소를 다시 확인해 주세요")));
		request(PASSWORD, "KIM@example.com").andExpect(content().string(containsString("지금 쓰는 이메일과 같아요")));
		landlordService.registerLocal(EmailAddress.of("taken@example.com"), "{bcrypt}hash", "다른사람");
		request(PASSWORD, "taken@example.com").andExpect(content().string(containsString("이미 다른 계정에서 쓰는 이메일이에요")));

		assertThat(mails.sent()).isEmpty();
		assertThat(jdbcTemplate.queryForObject("select failed_count from login_failure where landlord_id = ?",
				Integer.class, landlordId)).as("재인증 실패는 잠금 횟수에 포함").isEqualTo(1);
	}

	@Test
	void openingLinkDoesNotChangeEmail() throws Exception {
		request(PASSWORD, NEW_EMAIL);
		String token = mails.lastTokenTo(NEW_EMAIL);

		mockMvc.perform(get("/verify-email-change").param("token", token)).andExpect(status().isOk())
				.andExpect(header().string("Referrer-Policy", "no-referrer"))
				.andExpect(content().string(containsString("이메일 바꾸기 완료")));

		assertThat(currentEmail()).isEqualTo(EMAIL);
	}

	@Test
	void confirmingChangesEmailMarksVerifiedAndAllowsLoginWithNewEmail() throws Exception {
		request(PASSWORD, NEW_EMAIL);
		String token = mails.lastTokenTo(NEW_EMAIL);

		mockMvc.perform(post("/verify-email-change").with(csrf()).param("token", token))
				.andExpect(content().string(containsString("이메일을 바꿨어요")));

		assertThat(currentEmail()).isEqualTo(NEW_EMAIL);
		assertThat(landlordService.findVerifiedEmail(landlordId)).map(Object::toString).contains(NEW_EMAIL);
		assertThat(mails.sentTo(EMAIL)).as("이전 주소가 미인증이면 안내 안 함").isEmpty();
		jdbcTemplate.update("delete from rate_limit_bucket");
		mockMvc.perform(post("/login").with(csrf()).param("email", NEW_EMAIL).param("password", PASSWORD))
				.andExpect(redirectedUrl("/app"));
		mockMvc.perform(post("/login").with(csrf()).param("email", EMAIL).param("password", PASSWORD))
				.andExpect(redirectedUrl("/login?error"));

		mockMvc.perform(post("/verify-email-change").with(csrf()).param("token", token))
				.andExpect(content().string(containsString("링크가 만료됐거나 이미 사용됐어요")));
	}

	@Test
	void previousVerifiedAddressGetsNotice() throws Exception {
		jdbcTemplate.update("update landlord set email_verified_at = now() where id = ?", landlordId);
		request(PASSWORD, NEW_EMAIL);

		mockMvc.perform(post("/verify-email-change").with(csrf()).param("token", mails.lastTokenTo(NEW_EMAIL)));

		assertThat(mails.lastTo(EMAIL).subject()).contains("로그인 이메일이 바뀌었어요");
		assertThat(mails.lastTo(EMAIL).body()).contains(NEW_EMAIL);
	}

	@Test
	void addressTakenAfterRequestIsRejectedAtConfirmation() throws Exception {
		request(PASSWORD, NEW_EMAIL);
		String token = mails.lastTokenTo(NEW_EMAIL);
		landlordService.registerLocal(EmailAddress.of(NEW_EMAIL), "{bcrypt}hash", "먼저가입");

		mockMvc.perform(post("/verify-email-change").with(csrf()).param("token", token))
				.andExpect(content().string(containsString("이미 다른 계정에서 쓰는 이메일이에요")));
		assertThat(currentEmail()).isEqualTo(EMAIL);
	}

	@Test
	void linkExpiresAfter24Hours() throws Exception {
		request(PASSWORD, NEW_EMAIL);
		String token = mails.lastTokenTo(NEW_EMAIL);
		clock.advance(Duration.ofHours(24));

		mockMvc.perform(post("/verify-email-change").with(csrf()).param("token", token))
				.andExpect(content().string(containsString("링크가 만료됐거나 이미 사용됐어요")));
		assertThat(currentEmail()).isEqualTo(EMAIL);
	}

	@Test
	void requestRequiresLogin() throws Exception {
		mockMvc.perform(post("/app/settings/email").with(csrf()).param("currentPassword", PASSWORD).param("newEmail",
				NEW_EMAIL)).andExpect(redirectedUrl("/login"));
	}

	private ResultActions request(String currentPassword, String newEmail) throws Exception {
		return mockMvc.perform(post("/app/settings/email").with(csrf()).cookie(session)
				.param("currentPassword", currentPassword).param("newEmail", newEmail));
	}

	private String currentEmail() {
		return jdbcTemplate.queryForObject("select email from landlord where id = ?", String.class, landlordId);
	}

}
