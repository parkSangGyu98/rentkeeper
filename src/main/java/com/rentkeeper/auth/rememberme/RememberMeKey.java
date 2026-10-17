package com.rentkeeper.auth.rememberme;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.rentkeeper.common.crypto.SecureTokens;

/**
 * remember-me 키(환경변수 APP_REMEMBER_ME_KEY)를 정한다 (ADR-013).
 * <ul>
 * <li>운영: 반드시 있어야 하고 32자 이상이어야 한다. 없으면 앱이 뜨지 않는다.</li>
 * <li>local: 비어 있으면 실행할 때마다 임시 키를 만든다 (CI처럼 .env가 없는 환경에서도 테스트가 돌도록).</li>
 * </ul>
 * 참고: PersistentToken 방식에서 이 키는 자동 로그인 때 만드는 인증 객체를 서명·검증하는 데만 쓰인다. DB의 토큰과는
 * 무관해서 <strong>키를 바꿔도 기존 로그인 유지는 풀리지 않는다.</strong> 모두 끊으려면 persistent_logins를
 * 비운다.
 */
public final class RememberMeKey {

	static final int MIN_LENGTH = 32;

	private static final Logger log = LoggerFactory.getLogger(RememberMeKey.class);

	private RememberMeKey() {
	}

	public static String resolve(String configuredKey, boolean localProfile) {
		if (configuredKey == null || configuredKey.isBlank()) {
			if (localProfile) {
				log.warn("APP_REMEMBER_ME_KEY가 없어 개발용 임시 키를 씁니다 (local 프로필 전용).");
				return SecureTokens.newToken();
			}
			throw new IllegalStateException("APP_REMEMBER_ME_KEY 환경변수가 필요합니다 (32자 이상 무작위 값, ADR-013).");
		}
		if (configuredKey.length() < MIN_LENGTH || configuredKey.contains("${")) {
			throw new IllegalStateException("APP_REMEMBER_ME_KEY는 32자 이상의 무작위 값이어야 합니다 (ADR-013).");
		}
		return configuredKey;
	}

}
