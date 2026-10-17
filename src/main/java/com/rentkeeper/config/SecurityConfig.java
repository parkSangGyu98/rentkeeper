package com.rentkeeper.config;

import java.util.Map;

import javax.sql.DataSource;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.RememberMeServices;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;

import com.rentkeeper.auth.kakao.KakaoLoginSuccessHandler;
import com.rentkeeper.auth.kakao.KakaoOAuth2UserService;
import com.rentkeeper.auth.kakao.NoStoreAuthorizedClientRepository;
import com.rentkeeper.auth.rememberme.HashingPersistentTokenRepository;
import com.rentkeeper.auth.rememberme.HashingRememberMeServices;
import com.rentkeeper.auth.rememberme.RememberMeKey;
import com.rentkeeper.auth.rememberme.RememberMeUserDetailsService;
import com.rentkeeper.auth.web.LoginRateLimitFilter;
import com.rentkeeper.common.config.AppProperties;
import com.rentkeeper.common.ratelimit.RateLimiter;
import com.rentkeeper.landlord.application.LandlordService;

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
			"/images/**", "/favicon.ico", "/actuator/health",
			// 메일 링크는 다른 기기(로그인 안 된 상태)에서 열 수 있다
			"/verify-email", "/password-reset", "/password-reset/confirm", "/verify-email-change",
			// 카카오 로그인 시작과 카카오가 돌려보내는 주소 (Spring Security 필터가 처리)
			"/oauth2/authorization/kakao", "/login/oauth2/code/kakao"};

	static final int BCRYPT_STRENGTH = 12;

	static final String[] STATIC_PATHS = {"/css/**", "/js/**", "/images/**", "/favicon.ico"};

	/**
	 * 정적 파일 전용 체인 (ADR-013). 세션·remember-me 없이 보안 헤더만 붙인다.
	 * <p>
	 * 세션이 만료된 상태에서 브라우저가 페이지와 CSS를 동시에 요청하면, 두 요청이 같은 remember-me 쿠키로 동시에 자동 로그인하다
	 * 하나가 "이미 쓴 토큰"으로 보여 도난으로 오판된다(모든 기기 로그인 유지 해제). 정적 파일에서는 자동 로그인을 하지 않게 해 이를
	 * 막는다.
	 */
	@Bean
	@Order(1)
	SecurityFilterChain staticResourcesFilterChain(HttpSecurity http) throws Exception {
		http.securityMatcher(STATIC_PATHS).authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
				.requestCache(cache -> cache.disable())
				.securityContext(context -> context.securityContextRepository(new NullSecurityContextRepository()))
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
		applySecurityHeaders(http);
		return http.build();
	}

	@Bean
	@Order(2)
	SecurityFilterChain securityFilterChain(HttpSecurity http, RateLimiter rateLimiter,
			RememberMeServices rememberMeServices, ObjectProvider<ClientRegistrationRepository> clientRegistrations,
			KakaoOAuth2UserService kakaoUserService, KakaoLoginSuccessHandler kakaoSuccessHandler) throws Exception {
		// 로그인 시도 IP 한도 (ADR-012). 빈으로 등록하지 않는다: 빈이면 서블릿 필터로도 자동 등록되어 두 번 실행된다
		http.addFilterBefore(new LoginRateLimitFilter(rateLimiter), UsernamePasswordAuthenticationFilter.class);

		// 로그인 유지 30일 (ADR-013). 로그인 폼의 remember-me 체크박스를 켰을 때만 쿠키를 발급한다
		http.rememberMe(rememberMe -> rememberMe.rememberMeServices(rememberMeServices));

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

		// 카카오 로그인 (ADR-014). 키가 없으면(local·CI) 등록이 없어 켜지 않는다
		ClientRegistrationRepository kakaoRegistrations = clientRegistrations.getIfAvailable();
		if (kakaoRegistrations != null) {
			http.oauth2Login(oauth2 -> oauth2.loginPage("/login").clientRegistrationRepository(kakaoRegistrations)
					// 액세스·리프레시 토큰을 저장하지 않는다
					.authorizedClientRepository(new NoStoreAuthorizedClientRepository())
					.userInfoEndpoint(userInfo -> userInfo.userService(kakaoUserService))
					.successHandler(kakaoSuccessHandler)
					// 동의 취소, state 불일치 등: 어떤 이유든 같은 안내
					.failureUrl("/login?kakaoError"));
		}

		applySecurityHeaders(http);
		return http.build();
	}

	private static void applySecurityHeaders(HttpSecurity http) throws Exception {
		http.headers(headers -> headers.contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY))
				.referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
				.frameOptions(frame -> frame.deny()));
	}

	/** remember-me 토큰 저장소. 비밀번호 재설정·변경 때 그 사용자의 토큰을 모두 지우는 데도 쓴다 */
	@Bean
	HashingPersistentTokenRepository persistentTokenRepository(DataSource dataSource) {
		return new HashingPersistentTokenRepository(dataSource);
	}

	@Bean
	HashingRememberMeServices rememberMeServices(AppProperties appProperties, Environment environment,
			LandlordService landlordService, HashingPersistentTokenRepository persistentTokenRepository) {
		String key = RememberMeKey.resolve(appProperties.rememberMeKey(), environment.matchesProfiles("local"));
		return new HashingRememberMeServices(key, new RememberMeUserDetailsService(landlordService),
				persistentTokenRepository);
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
