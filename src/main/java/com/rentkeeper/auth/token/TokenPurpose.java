package com.rentkeeper.auth.token;

import java.time.Duration;

/**
 * 1회용 계정 토큰의 용도와 유효 시간 (M1-b 결정 3).
 */
public enum TokenPurpose {

	/** 가입한 이메일 인증 (24시간) */
	EMAIL_VERIFICATION(Duration.ofHours(24), true),

	/** 새 이메일 주소 인증 → 인증되면 이메일 변경 (24시간) */
	EMAIL_CHANGE(Duration.ofHours(24), true),

	/** 비밀번호 재설정 (30분, PRD AUTH-3) */
	PASSWORD_RESET(Duration.ofMinutes(30), false);

	private final Duration validity;

	private final boolean requiresTargetEmail;

	TokenPurpose(Duration validity, boolean requiresTargetEmail) {
		this.validity = validity;
		this.requiresTargetEmail = requiresTargetEmail;
	}

	public Duration validity() {
		return validity;
	}

	public boolean requiresTargetEmail() {
		return requiresTargetEmail;
	}

}
