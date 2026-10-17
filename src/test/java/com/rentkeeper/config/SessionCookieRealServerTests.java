package com.rentkeeper.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.rentkeeper.TestcontainersConfiguration;

import jakarta.servlet.http.HttpSession;

/**
 * 실제 내장 Tomcat에서 세션 쿠키와 타임아웃을 확인하는 유일한 테스트 (ADR-005).
 * <p>
 * Spring Boot는 MockMvc 환경과 실제 실행에서 세션 설정을 다른 경로로 읽는다. 다른 테스트는 MockMvc로 하고, 운영과
 * 같은 경로인 이 테스트 하나로 설정이 실제 응답에 반영되는지 확인한다.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfiguration.class, SessionCookieRealServerTests.SessionProbeController.class})
class SessionCookieRealServerTests {

	@LocalServerPort
	int port;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Test
	void sessionCookieAndTimeoutOnRealServer() throws Exception {
		HttpResponse<String> response = HttpClient.newHttpClient().send(
				HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/test/session-probe")).build(),
				HttpResponse.BodyHandlers.ofString());

		List<String> setCookies = response.headers().allValues("Set-Cookie");
		assertThat(setCookies).hasSize(1);
		String cookie = setCookies.get(0);
		assertThat(cookie).startsWith("SESSION=").contains("HttpOnly").contains("Secure").contains("SameSite=Lax");

		Integer maxInactive = jdbcTemplate.queryForObject(
				"select max_inactive_interval from spring_session where session_id = ?", Integer.class,
				response.body());
		assertThat(maxInactive).isEqualTo(2 * 60 * 60);
	}

	/**
	 * 세션을 하나 만들고 그 id를 돌려주는 테스트 전용 엔드포인트. (보안 설정이 들어오면 로그인 화면 요청으로 대체한다)
	 */
	@RestController
	static class SessionProbeController {

		@GetMapping("/test/session-probe")
		String probe(HttpSession session) {
			session.setAttribute("probe", "1");
			return session.getId();
		}

	}

}
