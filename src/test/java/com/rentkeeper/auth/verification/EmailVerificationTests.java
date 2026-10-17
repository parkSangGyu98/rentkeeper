package com.rentkeeper.auth.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
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
import org.springframework.test.web.servlet.MockMvc;

import com.rentkeeper.TestcontainersConfiguration;
import com.rentkeeper.auth.security.LandlordPrincipal;
import com.rentkeeper.common.mail.EmailMessage;
import com.rentkeeper.landlord.application.LandlordService;
import com.rentkeeper.support.MutableClock;
import com.rentkeeper.support.MutableClockConfiguration;
import com.rentkeeper.support.RecordingEmailSender;
import com.rentkeeper.support.RecordingEmailSenderConfiguration;
import com.rentkeeper.support.TestData;

/**
 * 이메일 인증: 가입 시 인증 메일, GET 확인 / POST 인증, 만료·재사용, 미인증 안내 띠, 재발송, 알림 주소 규칙.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RecordingEmailSenderConfiguration.class, MutableClockConfiguration.class})
class EmailVerificationTests {

	private static final String EMAIL = "kim@example.com";

	private static final String PASSWORD = "Haengbok-Villa-2026";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	RecordingEmailSender mails;

	@Autowired
	MutableClock clock;

	@Autowired
	LandlordService landlordService;

	long landlordId;

	@BeforeEach
	void signUp() throws Exception {
		clock.set(MutableClockConfiguration.START);
		TestData.reset(jdbcTemplate);
		mails.clear();
		mockMvc.perform(post("/signup").with(csrf()).param("email", "Kim@Example.com").param("password", PASSWORD)
				.param("passwordConfirm", PASSWORD).param("name", "김임대").param("agreeTerms", "true"))
				.andExpect(redirectedUrl("/app"));
		landlordId = jdbcTemplate.queryForObject("select id from landlord", Long.class);
	}

	@Test
	void signupSendsVerificationMailWithLinkToConfiguredBaseUrl() {
		EmailMessage mail = mails.lastTo(EMAIL);

		assertThat(mail.subject()).contains("이메일 인증");
		assertThat(mail.body()).contains("김임대님").contains("http://localhost:8080/verify-email?token=").contains("24시간");
		assertThat(landlordService.findVerifiedEmail(landlordId)).as("인증 전에는 알림 주소 없음").isEmpty();
	}

	@Test
	void openingLinkShowsConfirmationWithoutConsumingToken() throws Exception {
		String token = mails.lastTokenTo(EMAIL);

		mockMvc.perform(get("/verify-email").param("token", token)).andExpect(status().isOk())
				.andExpect(header().string("Referrer-Policy", "no-referrer"))
				.andExpect(content().string(containsString("인증 완료하기")));
		mockMvc.perform(get("/verify-email").param("token", token))
				.andExpect(content().string(containsString("인증 완료하기")));

		assertThat(isVerified()).isFalse();
	}

	@Test
	void submittingConfirmationVerifiesEmailOnce() throws Exception {
		String token = mails.lastTokenTo(EMAIL);

		mockMvc.perform(post("/verify-email").with(csrf()).param("token", token)).andExpect(status().isOk())
				.andExpect(content().string(containsString("이메일 인증이 끝났어요")));
		assertThat(isVerified()).isTrue();
		assertThat(landlordService.findVerifiedEmail(landlordId)).map(Object::toString).contains(EMAIL);

		mockMvc.perform(post("/verify-email").with(csrf()).param("token", token))
				.andExpect(content().string(containsString("링크가 만료됐거나 이미 사용됐어요")));
	}

	@Test
	void expiredOrBogusLinkIsRejected() throws Exception {
		String token = mails.lastTokenTo(EMAIL);
		clock.advance(Duration.ofHours(24));

		mockMvc.perform(get("/verify-email").param("token", token))
				.andExpect(content().string(containsString("링크가 만료됐거나 이미 사용됐어요")));
		mockMvc.perform(post("/verify-email").with(csrf()).param("token", token))
				.andExpect(content().string(containsString("링크가 만료됐거나 이미 사용됐어요")));
		mockMvc.perform(get("/verify-email").param("token", "bogus"))
				.andExpect(content().string(containsString("링크가 만료됐거나 이미 사용됐어요")));
		mockMvc.perform(get("/verify-email")).andExpect(status().isOk());
		assertThat(isVerified()).isFalse();
	}

	@Test
	void linkForOldAddressDoesNotVerifyChangedEmail() throws Exception {
		String token = mails.lastTokenTo(EMAIL);
		jdbcTemplate.update("update landlord set email = 'other@example.com' where id = ?", landlordId);

		mockMvc.perform(post("/verify-email").with(csrf()).param("token", token))
				.andExpect(content().string(containsString("링크가 만료됐거나 이미 사용됐어요")));
		assertThat(isVerified()).isFalse();
	}

	@Test
	void bannerIsShownOnlyWhileUnverified() throws Exception {
		mockMvc.perform(get("/app").with(user(principal())))
				.andExpect(content().string(containsString("이메일 인증이 아직 끝나지 않았어요")));
		mockMvc.perform(get("/")).andExpect(content().string(not(containsString("이메일 인증이 아직 끝나지 않았어요"))));

		mockMvc.perform(post("/verify-email").with(csrf()).param("token", mails.lastTokenTo(EMAIL)));

		mockMvc.perform(get("/app").with(user(principal())))
				.andExpect(content().string(not(containsString("이메일 인증이 아직 끝나지 않았어요"))));
	}

	@Test
	void resendIssuesNewLinkAndInvalidatesOldOne() throws Exception {
		String oldToken = mails.lastTokenTo(EMAIL);

		mockMvc.perform(post("/app/email-verification/resend").with(csrf()).with(user(principal())))
				.andExpect(redirectedUrl("/app"))
				.andExpect(flash().attribute("notice", containsString("인증 메일을 다시 보냈어요")));
		String newToken = mails.lastTokenTo(EMAIL);

		assertThat(newToken).isNotEqualTo(oldToken);
		mockMvc.perform(post("/verify-email").with(csrf()).param("token", oldToken))
				.andExpect(content().string(containsString("링크가 만료됐거나 이미 사용됐어요")));
		mockMvc.perform(post("/verify-email").with(csrf()).param("token", newToken))
				.andExpect(content().string(containsString("이메일 인증이 끝났어요")));
	}

	@Test
	void noMailIsResentOnceVerified() throws Exception {
		mockMvc.perform(post("/verify-email").with(csrf()).param("token", mails.lastTokenTo(EMAIL)));
		mails.clear();

		mockMvc.perform(post("/app/email-verification/resend").with(csrf()).with(user(principal())))
				.andExpect(redirectedUrl("/app"));
		assertThat(mails.sent()).isEmpty();
	}

	@Test
	void resendRequiresLogin() throws Exception {
		mockMvc.perform(post("/app/email-verification/resend").with(csrf())).andExpect(redirectedUrl("/login"));
	}

	private LandlordPrincipal principal() {
		return new LandlordPrincipal(landlordId, EMAIL, "김임대", true, null);
	}

	private boolean isVerified() {
		return jdbcTemplate.queryForObject("select email_verified_at is not null from landlord where id = ?",
				Boolean.class, landlordId);
	}

}
