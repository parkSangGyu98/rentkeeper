package com.rentkeeper.common.ratelimit;

import java.time.Duration;

/**
 * 레이트 리미트 규칙 (M1-b 결정 2, ADR-012). 수치를 바꾸면 ADR-012의 표도 함께 고친다.
 */
public enum RateLimitRule {

	/** 로그인 시도: IP당 10분에 30회 */
	LOGIN_PER_IP("login:ip", 30, Duration.ofMinutes(10)),

	/** 회원가입: IP당 1시간에 10회 (가입 화면의 "이미 가입된 이메일" 안내로 계정 존재를 캐는 것을 늦춘다) */
	SIGNUP_PER_IP("signup:ip", 10, Duration.ofHours(1)),

	/** 인증 메일 재발송: 계정당 1시간에 3회 */
	VERIFICATION_RESEND_PER_ACCOUNT("verify-resend:account", 3, Duration.ofHours(1)),

	/** 비밀번호 재설정 요청: IP당 1시간에 10회 */
	PASSWORD_RESET_REQUEST_PER_IP("reset-request:ip", 10, Duration.ofHours(1)),

	/** 비밀번호 재설정 요청: 이메일당 1시간에 3회 (키는 이메일의 SHA-256) */
	PASSWORD_RESET_REQUEST_PER_EMAIL("reset-request:email", 3, Duration.ofHours(1)),

	/** 비밀번호 재설정 완료: IP당 1시간에 20회 */
	PASSWORD_RESET_CONFIRM_PER_IP("reset-confirm:ip", 20, Duration.ofHours(1)),

	/** 세입자 링크 /t/** 의 모든 요청: IP당 1분에 30회 (PRD 4.7, M4 결정 9). 토큰이 틀린 요청도 센다 */
	TENANT_LINK_PER_IP("tenant-link:ip", 30, Duration.ofMinutes(1));

	private final String keyPrefix;

	private final int limit;

	private final Duration window;

	RateLimitRule(String keyPrefix, int limit, Duration window) {
		this.keyPrefix = keyPrefix;
		this.limit = limit;
		this.window = window;
	}

	public String key(String subject) {
		return keyPrefix + ":" + subject;
	}

	public int limit() {
		return limit;
	}

	public Duration window() {
		return window;
	}

}
