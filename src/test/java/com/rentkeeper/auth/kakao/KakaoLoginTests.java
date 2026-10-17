package com.rentkeeper.auth.kakao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

import com.rentkeeper.support.FakeKakaoServer;
import com.rentkeeper.support.FakeKakaoServer.Request;
import com.rentkeeper.support.KakaoIntegrationTest;
import com.rentkeeper.support.TestData;

import jakarta.servlet.http.Cookie;

/**
 * 카카오 로그인 (AUTH-2, ADR-014): 인가 요청, state 검증, 토큰 교환 방식, 기존 회원 로그인, 신규는 가입 단계로,
 * 토큰 미보관, 자동 병합 금지.
 */
class KakaoLoginTests extends KakaoIntegrationTest {

	private static final String KAKAO_ID = "1000000001";

	@Autowired
	JdbcTemplate jdbcTemplate;

	@BeforeEach
	void setUp() {
		TestData.reset(jdbcTemplate);
		kakao.reset();
	}

	@Test
	void authorizationRequestGoesToKakaoWithStateAndRegisteredRedirectUri() throws Exception {
		String location = startKakaoLogin().getResponse().getRedirectedUrl();

		assertThat(location).startsWith(kakao.baseUrl() + "/oauth/authorize?");
		assertThat(queryParam(location, "response_type")).isEqualTo("code");
		assertThat(queryParam(location, "client_id")).isEqualTo("test-client-id");
		assertThat(queryParam(location, "redirect_uri")).isEqualTo("http://localhost/login/oauth2/code/kakao");
		// 이메일 동의항목은 권한이 없어(비즈 앱 전환 전) 닉네임만 요청한다 (ADR-014)
		assertThat(queryParam(location, "scope")).isEqualTo("profile_nickname");
		assertThat(queryParam(location, "state")).isNotBlank();
		// PKCE: Security 7.1.1은 ClientRegistration의 requireProofKey 기본값이 true라 PKCE를
		// 보낸다 (ADR-014)
		assertThat(queryParam(location, "code_challenge")).isNotBlank();
		assertThat(queryParam(location, "code_challenge_method")).isEqualTo("S256");
	}

	@Test
	void tokenRequestIncludesPkceCodeVerifier() throws Exception {
		insertKakaoLandlord(KAKAO_ID, "ACTIVE");
		loginWithKakao(FakeKakaoServer.user(KAKAO_ID, null, false, false, "김카카오"));

		assertThat(kakao.requestsTo("/oauth/token").get(0).form()).containsKey("code_verifier")
				.extractingByKey("code_verifier").asString().isNotBlank();
	}

	@Test
	void forgedStateIsRejected() throws Exception {
		MvcResult start = startKakaoLogin();
		Cookie session = start.getResponse().getCookie("SESSION");

		mockMvc.perform(get("/login/oauth2/code/kakao").param("code", "fake-code").param("state", "forged-state")
				.cookie(session)).andExpect(redirectedUrl("/login?kakaoError")).andExpect(unauthenticated());
		assertThat(kakao.requestsTo("/oauth/token")).as("state가 틀리면 토큰 교환도 하지 않음").isEmpty();
	}

	@Test
	void existingKakaoMemberLogsInAsLandlordWithNewSessionId() throws Exception {
		long landlordId = insertKakaoLandlord(KAKAO_ID, "ACTIVE");
		MvcResult start = startKakaoLogin();
		String sessionBefore = start.getResponse().getCookie("SESSION").getValue();

		kakao.returnUser(FakeKakaoServer.user(KAKAO_ID, null, false, false, "김카카오"));
		MvcResult callback = completeKakaoLogin(start);

		assertThat(callback.getResponse().getRedirectedUrl()).isEqualTo("/app");
		Cookie sessionAfter = callback.getResponse().getCookie("SESSION");
		assertThat(sessionAfter.getValue()).as("로그인하면 세션 id가 바뀐다").isNotEqualTo(sessionBefore);
		assertThat(jdbcTemplate.queryForList(
				"select principal_name from spring_session where principal_name is not null", String.class))
				.containsExactly(String.valueOf(landlordId));
		// 새 세션 쿠키로 내 화면에 들어갈 수 있다 (Spring Session을 거치므로 응답 상태와 화면으로 확인한다)
		mockMvc.perform(get("/app").cookie(sessionAfter)).andExpect(status().isOk())
				.andExpect(content().string(containsString("김카카오")));
	}

	@Test
	void tokenRequestSendsClientSecretInBodyAndAccessTokenIsNotStored() throws Exception {
		insertKakaoLandlord(KAKAO_ID, "ACTIVE");
		loginWithKakao(FakeKakaoServer.user(KAKAO_ID, null, false, false, "김카카오"));

		Request token = kakao.requestsTo("/oauth/token").get(0);
		assertThat(token.form()).containsEntry("grant_type", "authorization_code")
				.containsEntry("client_id", "test-client-id").containsEntry("client_secret", "test-client-secret")
				.containsEntry("code", "fake-code");
		assertThat(kakao.requestsTo("/v2/user/me").get(0).authorization()).isEqualTo("Bearer fake-access-token");

		// 세션(DB)에 OAuth2 인가 정보(토큰)가 저장되지 않았다
		assertThat(jdbcTemplate.queryForList("select attribute_name from spring_session_attributes", String.class))
				.noneMatch(name -> name.toUpperCase().contains("AUTHORIZED_CLIENT"));
		assertThat(jdbcTemplate.queryForObject(
				"select count(*) from spring_session_attributes where position(convert_to('fake-access-token', 'UTF8') in attribute_bytes) > 0",
				Integer.class)).isZero();
	}

	@Test
	void newKakaoUserGoesToSignupStepWithoutCreatingAccount() throws Exception {
		MvcResult callback = loginWithKakao(FakeKakaoServer.user("1000000099", "new@example.com", true, true, "새카카오"));

		assertThat(callback.getResponse().getRedirectedUrl()).isEqualTo("/signup/kakao");
		assertThat(jdbcTemplate.queryForObject("select count(*) from landlord", Integer.class)).isZero();
		Cookie session = callback.getResponse().getCookie("SESSION");
		mockMvc.perform(get("/app").cookie(session))
				.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
	}

	@Test
	void localAccountWithSameEmailIsNotMerged() throws Exception {
		jdbcTemplate.update("insert into landlord (auth_provider, email, password_hash, name, created_at, updated_at) "
				+ "values ('LOCAL', 'kim@example.com', '{bcrypt}x', '이메일계정', now(), now())");

		MvcResult callback = loginWithKakao(FakeKakaoServer.user(KAKAO_ID, "kim@example.com", true, true, "김카카오"));

		assertThat(callback.getResponse().getRedirectedUrl()).as("같은 이메일이어도 새 가입 단계로").isEqualTo("/signup/kakao");
	}

	@Test
	void withdrawnKakaoAccountIsTreatedAsNewSignup() throws Exception {
		insertKakaoLandlord(KAKAO_ID, "WITHDRAWN");

		MvcResult callback = loginWithKakao(FakeKakaoServer.user(KAKAO_ID, null, false, false, "김카카오"));

		assertThat(callback.getResponse().getRedirectedUrl()).isEqualTo("/signup/kakao");
	}

	@Test
	void loginPageShowsKakaoButtonAsLink() throws Exception {
		mockMvc.perform(get("/login"))
				.andExpect(content().string(containsString("href=\"/oauth2/authorization/kakao\"")))
				.andExpect(content().string(containsString("카카오로 시작하기")));
		mockMvc.perform(get("/login").param("kakaoError", ""))
				.andExpect(content().string(containsString("카카오 로그인을 끝내지 못했어요")));
	}

	private long insertKakaoLandlord(String kakaoId, String status) {
		jdbcTemplate.update("insert into landlord (auth_provider, kakao_id, name, status, withdrawn_at, created_at, "
				+ "updated_at) values ('KAKAO', ?, '김카카오', ?, " + ("WITHDRAWN".equals(status) ? "now()" : "null")
				+ ", now(), now())", kakaoId, status);
		return jdbcTemplate.queryForObject("select max(id) from landlord", Long.class);
	}

}
