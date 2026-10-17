package com.rentkeeper.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

/**
 * 세션 쿠키(SESSION) 속성을 코드 한 곳에서 정한다 (PRD 8.1, ADR-005).
 * <p>
 * server.servlet.session.cookie.* 프로퍼티를 쓰지 않는 이유: Spring Boot는 내장 Tomcat으로 실행할
 * 때만 그 프로퍼티를 세션 쿠키에 반영하고, MockMvc 테스트(내장 서버 없음)에서는 서블릿 컨텍스트의 기본 쿠키 설정을 쓴다. 이 빈을
 * 직접 정의하면 두 경우 모두 같은 설정을 쓰므로 테스트가 운영과 같은 쿠키를 검증한다.
 */
@Configuration(proxyBeanMethods = false)
public class SessionCookieConfig {

	@Bean
	CookieSerializer cookieSerializer() {
		DefaultCookieSerializer serializer = new DefaultCookieSerializer();
		// 쿠키 이름은 Spring Session 기본값(SESSION)을 그대로 쓴다
		// 자바스크립트에서 읽을 수 없게 한다 (XSS로 세션 탈취 방지)
		serializer.setUseHttpOnlyCookie(true);
		// HTTPS로만 보낸다. 로컬 http://localhost는 Chrome·Edge·Firefox가 안전한 출처로 취급해 그대로 동작한다
		serializer.setUseSecureCookie(true);
		// 다른 사이트에서 시작된 POST 요청에는 쿠키를 보내지 않는다 (CSRF 방어를 한 겹 더)
		serializer.setSameSite("Lax");
		return serializer;
	}

}
