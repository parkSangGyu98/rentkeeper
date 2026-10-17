package com.rentkeeper.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.rentkeeper.TestcontainersConfiguration;
import com.rentkeeper.auth.security.LandlordPrincipal;
import com.rentkeeper.landlord.application.LandlordService;
import com.rentkeeper.landlord.domain.EmailAddress;
import com.rentkeeper.support.RecordingEmailSender;
import com.rentkeeper.support.RecordingEmailSenderConfiguration;
import com.rentkeeper.support.TestData;

/**
 * 화면에 적용된 레이트 리미트: 로그인(IP), 가입(IP), 인증 메일 재발송(계정) (ADR-012).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RecordingEmailSenderConfiguration.class})
class RateLimitWebTests {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	LandlordService landlordService;

	@Autowired
	RecordingEmailSender mails;

	@BeforeEach
	void setUp() {
		TestData.reset(jdbcTemplate);
		mails.clear();
	}

	@Test
	void loginAttemptsArePerIpAndReturn429OverLimit() throws Exception {
		for (int i = 0; i < 30; i++) {
			mockMvc.perform(login("10.0.0.1")).andExpect(status().is3xxRedirection());
		}
		mockMvc.perform(login("10.0.0.1")).andExpect(status().isTooManyRequests());
		mockMvc.perform(login("10.0.0.2")).andExpect(status().is3xxRedirection());
	}

	@Test
	void signupIsLimitedPerIp() throws Exception {
		for (int i = 0; i < 10; i++) {
			mockMvc.perform(signup("10.0.0.1")).andExpect(status().isOk());
		}
		mockMvc.perform(signup("10.0.0.1")).andExpect(status().isTooManyRequests());
		mockMvc.perform(signup("10.0.0.2")).andExpect(status().isOk());
	}

	@Test
	void verificationResendIsLimitedPerAccount() throws Exception {
		long id = landlordService.registerLocal(EmailAddress.of("kim@example.com"), "{bcrypt}hash", "김임대");
		LandlordPrincipal principal = new LandlordPrincipal(id, "kim@example.com", "김임대", true, null);

		for (int i = 0; i < 3; i++) {
			mockMvc.perform(post("/app/email-verification/resend").with(csrf()).with(user(principal)))
					.andExpect(flash().attribute("notice", containsString("다시 보냈어요")));
		}
		mockMvc.perform(post("/app/email-verification/resend").with(csrf()).with(user(principal)))
				.andExpect(flash().attribute("notice", containsString("1시간에 3번까지")));

		assertThat(mails.sentTo("kim@example.com")).hasSize(3);
	}

	// 없는 계정으로 시도한다 (계정 잠금과 섞이지 않게)
	private static MockHttpServletRequestBuilder login(String ip) {
		return post("/login").with(csrf()).with(remoteAddr(ip)).param("email", "nobody@example.com").param("password",
				"whatever-password");
	}

	// 형식 오류로 가입은 실패하지만, 요청 자체는 세어진다
	private static MockHttpServletRequestBuilder signup(String ip) {
		return post("/signup").with(csrf()).with(remoteAddr(ip)).param("email", "").param("password", "")
				.param("passwordConfirm", "").param("name", "");
	}

	private static RequestPostProcessor remoteAddr(String ip) {
		return request -> {
			request.setRemoteAddr(ip);
			return request;
		};
	}

}
