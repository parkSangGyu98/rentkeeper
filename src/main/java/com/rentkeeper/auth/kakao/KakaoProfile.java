package com.rentkeeper.auth.kakao;

import java.util.Map;

import com.rentkeeper.landlord.domain.EmailAddress;
import com.rentkeeper.landlord.domain.Landlord;

/**
 * 카카오 사용자 정보 중 우리가 쓰는 것만 (ADR-014, 최소 수집).
 *
 * @param kakaoId
 *            회원번호 (숫자 문자열)
 * @param email
 *            동의했고 유효한(is_email_valid) 이메일. 없으면 null
 * @param emailTrusted
 *            이메일이 유효하고 카카오에서 인증된 것(is_email_verified)이면 true → 우리 인증 메일 없이 인증된
 *            것으로 저장 (M1-c 결정 5)
 * @param nickname
 *            닉네임 (이름 기본값으로만 쓴다, 50자까지). 없으면 null
 */
public record KakaoProfile(String kakaoId, String email, boolean emailTrusted, String nickname) {

	/**
	 * @param emailScopeRequested
	 *            이메일 동의항목(account_email)을 요청했는지. 요청하지 않았으면 응답에 이메일이 있어도 쓰지 않는다:
	 *            이메일을 비워 두고 인증된 것으로도 보지 않는다 (ADR-014, 지금 기본 경로)
	 */
	@SuppressWarnings("unchecked")
	public static KakaoProfile from(Map<String, Object> attributes, boolean emailScopeRequested) {
		Object id = attributes.get("id");
		String kakaoId = id == null ? "" : String.valueOf(id);
		if (!kakaoId.matches("[0-9]+")) {
			throw new IllegalArgumentException("kakao id must be numeric");
		}
		Map<String, Object> account = attributes.get("kakao_account") instanceof Map<?, ?> map
				? (Map<String, Object>) map
				: Map.of();

		String email = null;
		boolean emailTrusted = false;
		// is_email_valid=false면 다른 카카오계정에 쓰여 만료된 주소다 (카카오 문서) → 쓰지 않는다
		if (emailScopeRequested && account.get("email") instanceof String rawEmail
				&& Boolean.TRUE.equals(account.get("is_email_valid"))) {
			try {
				email = EmailAddress.of(rawEmail).value();
				emailTrusted = Boolean.TRUE.equals(account.get("is_email_verified"));
			} catch (EmailAddress.InvalidEmailException e) {
				email = null; // 우리 형식 규칙(ASCII 등)에 맞지 않으면 사용자가 직접 입력하게 한다
			}
		}

		String nickname = null;
		if (account.get("profile") instanceof Map<?, ?> profile && profile.get("nickname") instanceof String raw
				&& !raw.isBlank()) {
			String stripped = raw.strip();
			nickname = stripped.codePointCount(0, stripped.length()) > Landlord.NAME_MAX_LENGTH
					? stripped.substring(0, stripped.offsetByCodePoints(0, Landlord.NAME_MAX_LENGTH))
					: stripped;
		}
		return new KakaoProfile(kakaoId, email, emailTrusted, nickname);
	}

	// 이메일·닉네임(개인정보)은 로그에 남기지 않는다
	@Override
	public String toString() {
		return "KakaoProfile[kakaoId=" + kakaoId + "]";
	}

}
