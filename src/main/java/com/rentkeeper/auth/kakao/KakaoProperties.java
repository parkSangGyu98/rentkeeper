package com.rentkeeper.auth.kakao;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.StringUtils;

/**
 * 카카오 로그인 설정 (ADR-014). 키 값은 환경변수(로컬은 .env)로만 받는다.
 *
 * @param clientId
 *            REST API 키 (KAKAO_CLIENT_ID)
 * @param clientSecret
 *            클라이언트 시크릿 (KAKAO_CLIENT_SECRET). 카카오는 토큰 요청 본문으로 받는다
 * @param adminKey
 *            서비스 앱 어드민 키 (KAKAO_ADMIN_KEY). 탈퇴 시 연결 끊기에 쓴다
 * @param authorizationUri
 *            테스트에서 가짜 카카오 서버로 바꿀 수 있게 주소도 설정으로 둔다
 */
@ConfigurationProperties("app.kakao")
public record KakaoProperties(String clientId, String clientSecret, String adminKey,
		@DefaultValue("https://kauth.kakao.com/oauth/authorize") String authorizationUri,
		@DefaultValue("https://kauth.kakao.com/oauth/token") String tokenUri,
		@DefaultValue("https://kapi.kakao.com/v2/user/me") String userInfoUri,
		@DefaultValue("https://kapi.kakao.com/v1/user/unlink") String unlinkUri) {

	public boolean enabled() {
		return StringUtils.hasText(clientId);
	}

	/**
	 * 설정이 올바른지 확인한다 (M1-c 결정 3·10).
	 * <ul>
	 * <li>셋 다 없음: local·CI는 카카오 로그인을 끈다. 운영(prod)은 기동 실패.</li>
	 * <li>일부만 있음: 설정 실수로 보고 기동 실패 (예: 로그인은 되는데 탈퇴 때 연결 끊기를 못 하는 상태를 막는다).</li>
	 * </ul>
	 */
	public void validate(boolean prod) {
		boolean hasId = StringUtils.hasText(clientId);
		boolean hasSecret = StringUtils.hasText(clientSecret);
		boolean hasAdminKey = StringUtils.hasText(adminKey);
		if (!hasId && !hasSecret && !hasAdminKey) {
			if (prod) {
				throw new IllegalStateException(
						"운영(prod)에는 KAKAO_CLIENT_ID, KAKAO_CLIENT_SECRET, KAKAO_ADMIN_KEY가 모두 필요합니다 (ADR-014).");
			}
			return;
		}
		if (!(hasId && hasSecret && hasAdminKey)) {
			throw new IllegalStateException(
					"KAKAO_CLIENT_ID, KAKAO_CLIENT_SECRET, KAKAO_ADMIN_KEY는 모두 있거나 모두 없어야 합니다 (ADR-014).");
		}
	}

	// 키 값이 로그·예외 메시지에 찍히지 않게 한다
	@Override
	public String toString() {
		return "KakaoProperties[enabled=" + enabled() + "]";
	}

}
