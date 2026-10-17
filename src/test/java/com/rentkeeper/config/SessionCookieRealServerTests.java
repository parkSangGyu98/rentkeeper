package com.rentkeeper.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.rentkeeper.TestcontainersConfiguration;

/**
 * 실제 내장 Tomcat에서 세션 쿠키와 타임아웃을 확인하는 유일한 테스트 (ADR-005).
 * <p>
 * Spring Boot는 MockMvc 환경과 실제 실행에서 세션 설정을 다른 경로로 읽는다. 다른 테스트는 MockMvc로 하고, 운영과
 * 같은 경로인 이 테스트 하나로 설정이 실제 응답에 반영되는지 확인한다. 로그인 화면은 폼에 CSRF 토큰을 넣으면서 세션을 만든다.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class SessionCookieRealServerTests {

	@LocalServerPort
	int port;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Test
	void sessionCookieAndTimeoutOnRealServer() throws Exception {
		HttpResponse<String> response = HttpClient.newHttpClient().send(
				HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/login")).build(),
				HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(200);
		List<String> setCookies = response.headers().allValues("Set-Cookie");
		assertThat(setCookies).hasSize(1);
		String cookie = setCookies.get(0);
		assertThat(cookie).startsWith("SESSION=").contains("HttpOnly").contains("Secure").contains("SameSite=Lax");

		// Spring Session은 쿠키 값에 세션 id를 Base64로 넣는다
		String encoded = cookie.substring("SESSION=".length(), cookie.indexOf(';'));
		String sessionId = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
		Integer maxInactive = jdbcTemplate.queryForObject(
				"select max_inactive_interval from spring_session where session_id = ?", Integer.class, sessionId);
		assertThat(maxInactive).isEqualTo(2 * 60 * 60);
	}

}
