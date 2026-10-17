package com.rentkeeper.auth.kakao;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.util.StringUtils;

/**
 * 카카오 로그인 등록 (ADR-014). 카카오는 Spring Security의 기본 제공자 목록에 없어서 주소를 직접 등록한다.
 * <p>
 * spring.security.oauth2.client.* 프로퍼티 대신 코드로 등록하는 이유: 키가 없을 때(local·CI) Boot는
 * 빈 client-id로 기동에 실패한다. 키가 있을 때만 등록하고, 없으면 카카오 로그인을 끈다 (M1-c 결정 10).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KakaoProperties.class)
public class KakaoOAuth2Config {

	public static final String REGISTRATION_ID = "kakao";

	private static final Logger log = LoggerFactory.getLogger(KakaoOAuth2Config.class);

	KakaoOAuth2Config(KakaoProperties properties, Environment environment) {
		properties.validate(environment.matchesProfiles("prod"));
		if (!properties.enabled()) {
			log.info("카카오 로그인 꺼짐: KAKAO_CLIENT_ID 등이 설정되지 않았습니다 (local·CI 전용).");
		}
	}

	@Bean
	@Conditional(KakaoEnabledCondition.class)
	ClientRegistrationRepository clientRegistrationRepository(KakaoProperties properties) {
		return new InMemoryClientRegistrationRepository(kakaoRegistration(properties));
	}

	static ClientRegistration kakaoRegistration(KakaoProperties properties) {
		return ClientRegistration.withRegistrationId(REGISTRATION_ID).clientId(properties.clientId())
				.clientSecret(properties.clientSecret())
				// 카카오는 client_secret을 토큰 요청 본문(POST)으로 받는다
				.clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
				.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
				// 카카오 콘솔에 등록한 리다이렉트 URI와 같은 형태 (로컬:
				// http://localhost:8080/login/oauth2/code/kakao)
				.redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
				// 최소 수집: 이메일(선택 동의)과 닉네임만
				.scope("account_email", "profile_nickname").authorizationUri(properties.authorizationUri())
				.tokenUri(properties.tokenUri()).userInfoUri(properties.userInfoUri())
				// 사용자 정보 응답의 최상위 "id"(회원번호)
				.userNameAttributeName("id").clientName("카카오").build();
	}

	/** app.kakao.client-id에 값이 있을 때만 카카오 등록을 만든다 */
	static class KakaoEnabledCondition implements Condition {

		@Override
		public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
			return StringUtils.hasText(context.getEnvironment().getProperty("app.kakao.client-id"));
		}

	}

}
