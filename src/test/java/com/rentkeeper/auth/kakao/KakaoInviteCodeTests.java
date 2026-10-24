package com.rentkeeper.auth.kakao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import com.rentkeeper.support.FakeKakaoServer;
import com.rentkeeper.support.KakaoIntegrationTest;
import com.rentkeeper.support.TestData;

import jakarta.servlet.http.Cookie;

/** 카카오로 처음 온 사람도 초대 코드가 있어야 계정을 만든다 (M6 커밋 3, 결정 3) */
@TestPropertySource(properties = "app.signup.invite-code=kakao-invite-code-2026")
class KakaoInviteCodeTests extends KakaoIntegrationTest {

	@Autowired
	JdbcTemplate jdbc;

	@BeforeEach
	void setUp() {
		TestData.reset(jdbc);
		kakao.reset();
	}

	@Test
	void kakaoSignupNeedsTheInviteCode() throws Exception {
		Cookie pending = loginWithKakao(FakeKakaoServer.user("2000000042", null, false, false, "김카카오")).getResponse()
				.getCookie("SESSION");
		assertThat(mockMvc.perform(get("/signup/kakao").cookie(pending)).andReturn().getResponse().getContentAsString())
				.contains("name=\"inviteCode\"");

		MvcResult wrong = submit(pending, "wrong");

		assertThat(wrong.getResponse().getContentAsString()).contains("초대 코드가 맞지 않아요");
		assertThat(jdbc.queryForObject("select count(*) from landlord", Integer.class)).isZero();

		MvcResult right = submit(pending, "kakao-invite-code-2026");

		assertThat(right.getResponse().getRedirectedUrl()).isEqualTo("/app");
		assertThat(jdbc.queryForObject("select count(*) from landlord", Integer.class)).isEqualTo(1);
	}

	private MvcResult submit(Cookie pending, String code) throws Exception {
		return mockMvc
				.perform(post("/signup/kakao").with(csrf()).cookie(pending).param("name", "김임대")
						.param("notificationEmail", "").param("agreeTerms", "true").param("inviteCode", code))
				.andReturn();
	}

}
