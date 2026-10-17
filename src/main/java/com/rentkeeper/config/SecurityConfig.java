package com.rentkeeper.config;

import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;

import jakarta.servlet.DispatcherType;

/**
 * 웹 보안 기본 구성 (PRD 8.1, ADR-008, ADR-009).
 * <ul>
 * <li>접근 제어는 기본 거부: 허용 목록에 없는 경로는 모두 막는다. 새 화면을 만들고 등록을 잊으면 열리지 않고 막히는 쪽으로
 * 실패한다.</li>
 * <li>CSRF는 Spring Security 기본값(활성). Thymeleaf {@code th:action} 폼에 토큰이 자동으로
 * 들어간다.</li>
 * <li>세션 쿠키 속성은 SessionCookieConfig, 타임아웃은 spring.session.timeout
 * (ADR-005).</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

	/**
	 * 외부 자원을 쓰지 않고, 인라인 스크립트·스타일도 허용하지 않는다. 템플릿은 TemplateGuardTest가 이 규칙을 지키는지
	 * 검사한다.
	 */
	static final String CONTENT_SECURITY_POLICY = String.join("; ", "default-src 'self'", "script-src 'self'",
			"style-src 'self'", "img-src 'self' data:", "font-src 'self'", "form-action 'self'",
			"frame-ancestors 'none'", "base-uri 'self'", "object-src 'none'");

	static final String[] PUBLIC_PATHS = {"/", "/login", "/signup", "/terms", "/privacy", "/error", "/css/**", "/js/**",
			"/images/**", "/favicon.ico", "/actuator/health"};

	static final int BCRYPT_STRENGTH = 12;

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		http.authorizeHttpRequests(authorize -> authorize
				// 오류 화면 전달(/error)과 내부 forward는 원래 요청에서 이미 권한 검사를 거쳤다
				.dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.FORWARD).permitAll()
				.requestMatchers(PUBLIC_PATHS).permitAll().requestMatchers("/app/**").hasRole("LANDLORD").anyRequest()
				.denyAll());

		http.formLogin(form -> form.loginPage("/login").usernameParameter("email").passwordParameter("password")
				.defaultSuccessUrl("/app", true)
				// 없는 계정인지 비밀번호가 틀렸는지 구분하지 않고 같은 안내를 보여 준다
				.failureUrl("/login?error"));

		// CSRF가 켜져 있으면 로그아웃은 POST만 받는다 (다른 사이트의 링크·이미지로 강제 로그아웃되는 것을 막는다)
		http.logout(logout -> logout.logoutUrl("/logout").logoutSuccessUrl("/login?logout"));

		http.headers(headers -> headers.contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY))
				.referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
				.frameOptions(frame -> frame.deny()));

		return http.build();
	}

	/**
	 * BCrypt cost 12. 저장값에 {@code {bcrypt}} 접두사가 붙어, 나중에 알고리즘이나 cost를 바꿔도 기존 해시를
	 * 구분해 검증할 수 있다.
	 */
	@Bean
	PasswordEncoder passwordEncoder() {
		return new DelegatingPasswordEncoder("bcrypt", Map.of("bcrypt", new BCryptPasswordEncoder(BCRYPT_STRENGTH)));
	}

}
