package com.rentkeeper.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.CookieSerializer.CookieValue;

import com.rentkeeper.TestcontainersConfiguration;

/**
 * Flyway(V3)로 만든 세션 테이블이 Spring Session JDBC와 맞는지 실제 저장·조회로 확인한다 (ADR-005).
 * 타임아웃은 spring.session.timeout에서 오므로 이 MockMvc 환경 테스트도 운영과 같은 값을 본다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SpringSessionJdbcTests {

	@Autowired
	FindByIndexNameSessionRepository<? extends Session> sessionRepository;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	CookieSerializer cookieSerializer;

	@Test
	void savesSessionWithTwoHourTimeoutAndFindsItByPrincipalName() {
		String sessionId = saveSessionFor("42");

		assertThat(sessionRepository.findById(sessionId)).isNotNull();
		assertThat(sessionRepository.findByPrincipalName("42")).containsKey(sessionId);
		Integer maxInactive = jdbcTemplate.queryForObject(
				"select max_inactive_interval from spring_session where session_id = ?", Integer.class, sessionId);
		assertThat(Duration.ofSeconds(maxInactive)).isEqualTo(Duration.ofHours(2));

		sessionRepository.deleteById(sessionId);
		assertThat(jdbcTemplate.queryForObject("select count(*) from spring_session_attributes where "
				+ "session_primary_id not in (select primary_id from spring_session)", Integer.class)).isZero();
	}

	@Test
	void mockEnvironmentUsesSameCookieSettingsAsRealServer() {
		// Boot가 MockMvc 환경에서 만드는 기본 쿠키 설정이 아니라 SessionCookieConfig의 빈이 쓰여야 한다
		MockHttpServletRequest request = new MockHttpServletRequest();
		MockHttpServletResponse response = new MockHttpServletResponse();
		cookieSerializer.writeCookieValue(new CookieValue(request, response, "value"));

		assertThat(response.getHeader("Set-Cookie")).startsWith("SESSION=").contains("HttpOnly").contains("Secure")
				.contains("SameSite=Lax");
	}

	@SuppressWarnings("unchecked")
	private String saveSessionFor(String principalName) {
		FindByIndexNameSessionRepository<Session> repository = (FindByIndexNameSessionRepository<Session>) sessionRepository;
		Session session = repository.createSession();
		session.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, principalName);
		repository.save(session);
		return session.getId();
	}

}
