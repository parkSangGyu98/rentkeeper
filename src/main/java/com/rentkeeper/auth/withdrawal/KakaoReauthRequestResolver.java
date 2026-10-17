package com.rentkeeper.auth.withdrawal;

import java.time.Clock;

import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 카카오 인가 요청을 만든다. 탈퇴 의사 확인(KakaoWithdrawalIntent)이 유효한 세션이면
 * {@code prompt=login}을 붙여 카카오에 로그인돼 있어도 아이디·비밀번호를 다시 입력하게 한다 (M1-c 결정 2).
 * <p>
 * 카카오톡 인앱 브라우저는 prompt=login을 지원하지 않는다(카카오 문서). 탈퇴 화면에서 다른 브라우저로 열도록 안내한다.
 */
public class KakaoReauthRequestResolver implements OAuth2AuthorizationRequestResolver {

	private final OAuth2AuthorizationRequestResolver normal;

	private final OAuth2AuthorizationRequestResolver reauth;

	private final Clock clock;

	public KakaoReauthRequestResolver(ClientRegistrationRepository registrations, Clock clock) {
		String baseUri = OAuth2AuthorizationRequestRedirectFilter.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI;
		this.normal = new DefaultOAuth2AuthorizationRequestResolver(registrations, baseUri);
		DefaultOAuth2AuthorizationRequestResolver withPrompt = new DefaultOAuth2AuthorizationRequestResolver(
				registrations, baseUri);
		withPrompt.setAuthorizationRequestCustomizer(
				builder -> builder.additionalParameters(params -> params.put("prompt", "login")));
		this.reauth = withPrompt;
		this.clock = clock;
	}

	@Override
	public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
		return choose(request).resolve(request);
	}

	@Override
	public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
		return choose(request).resolve(request, clientRegistrationId);
	}

	private OAuth2AuthorizationRequestResolver choose(HttpServletRequest request) {
		KakaoWithdrawalIntent intent = KakaoWithdrawalIntent.peek(request);
		return intent != null && intent.isValidAt(clock.instant()) ? reauth : normal;
	}

}
