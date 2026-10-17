package com.rentkeeper.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriComponentsBuilder;

import com.rentkeeper.TestcontainersConfiguration;

import jakarta.servlet.http.Cookie;

/**
 * 가짜 카카오 서버로 카카오 로그인 경로 전체를 시험하는 통합 테스트의 공통 설정. 하위 클래스들이 같은 테스트 컨텍스트를 공유한다.
 * <p>
 * 카카오 키는 테스트용 가짜 값으로 덮어쓴다. 로컬 .env의 실제 키는 테스트에서 쓰이지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RecordingEmailSenderConfiguration.class})
public abstract class KakaoIntegrationTest {

	protected static final FakeKakaoServer kakao = FakeKakaoServer.start();

	@DynamicPropertySource
	static void kakaoProperties(DynamicPropertyRegistry registry) {
		registry.add("app.kakao.client-id", () -> "test-client-id");
		registry.add("app.kakao.client-secret", () -> "test-client-secret");
		registry.add("app.kakao.admin-key", () -> "test-admin-key");
		registry.add("app.kakao.authorization-uri", () -> kakao.baseUrl() + "/oauth/authorize");
		registry.add("app.kakao.token-uri", () -> kakao.baseUrl() + "/oauth/token");
		registry.add("app.kakao.user-info-uri", () -> kakao.baseUrl() + "/v2/user/me");
		registry.add("app.kakao.unlink-uri", () -> kakao.baseUrl() + "/v1/user/unlink");
	}

	@Autowired
	protected MockMvc mockMvc;

	/** 카카오 로그인 시작: 우리 서버가 카카오 인가 화면으로 보내는 응답 */
	protected MvcResult startKakaoLogin() throws Exception {
		return mockMvc.perform(get("/oauth2/authorization/kakao")).andReturn();
	}

	/** 사용자가 카카오에서 동의를 마치고 돌아온 것처럼 콜백을 호출한다 (state는 시작 응답에서 꺼낸다) */
	protected MvcResult completeKakaoLogin(MvcResult start) throws Exception {
		String state = queryParam(start.getResponse().getRedirectedUrl(), "state");
		Cookie session = start.getResponse().getCookie("SESSION");
		return mockMvc.perform(
				get("/login/oauth2/code/kakao").param("code", "fake-code").param("state", state).cookie(session))
				.andReturn();
	}

	protected MvcResult loginWithKakao(String userJson) throws Exception {
		kakao.returnUser(userJson);
		return completeKakaoLogin(startKakaoLogin());
	}

	protected static String queryParam(String url, String name) {
		String raw = UriComponentsBuilder.fromUriString(url).build().getQueryParams().getFirst(name);
		return raw == null ? null : URLDecoder.decode(raw, StandardCharsets.UTF_8);
	}

}
