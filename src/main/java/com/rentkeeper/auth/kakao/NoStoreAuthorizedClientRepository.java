package com.rentkeeper.auth.kakao;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 카카오 액세스·리프레시 토큰을 <strong>어디에도 저장하지 않는다</strong> (ADR-014).
 * <p>
 * 우리는 로그인할 때 사용자 정보를 한 번 읽는 데만 토큰을 쓴다. Boot 기본값은 토큰을 메모리에 보관하고, 세션 방식은
 * DB(spring_session)에 저장한다. 쓰지 않는 토큰을 남겨 둘 이유가 없고, 연결 끊기(탈퇴)는 어드민 키로 한다.
 */
public class NoStoreAuthorizedClientRepository implements OAuth2AuthorizedClientRepository {

	@Override
	public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(String clientRegistrationId,
			Authentication principal, HttpServletRequest request) {
		return null;
	}

	@Override
	public void saveAuthorizedClient(OAuth2AuthorizedClient authorizedClient, Authentication principal,
			HttpServletRequest request, HttpServletResponse response) {
		// 저장하지 않는다
	}

	@Override
	public void removeAuthorizedClient(String clientRegistrationId, Authentication principal,
			HttpServletRequest request, HttpServletResponse response) {
		// 저장한 것이 없다
	}

}
