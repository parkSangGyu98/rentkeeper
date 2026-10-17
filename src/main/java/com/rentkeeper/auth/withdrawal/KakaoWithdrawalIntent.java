package com.rentkeeper.auth.withdrawal;

import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

/**
 * 카카오 계정 탈퇴의 "탈퇴하겠다는 의사 확인" (M1-c 결정 2). 탈퇴 화면에서 POST로 확인하면 세션에 저장되고, 10분 안에
 * 카카오 재로그인을 마치면 그 콜백에서 바로 탈퇴한다 (결정 12).
 * <p>
 * 세션(DB)에 직렬화되어 저장되므로 회원번호 같은 개인정보는 넣지 않는다.
 *
 * @param landlordId
 *            탈퇴를 요청한 계정. 카카오 재로그인 결과가 이 계정이어야 탈퇴한다
 */
public record KakaoWithdrawalIntent(long landlordId, Instant expiresAt) implements Serializable {

	public static final Duration VALIDITY = Duration.ofMinutes(10);

	static final String SESSION_ATTRIBUTE = KakaoWithdrawalIntent.class.getName();

	public boolean isValidAt(Instant now) {
		return now.isBefore(expiresAt);
	}

	void store(HttpServletRequest request) {
		request.getSession().setAttribute(SESSION_ATTRIBUTE, this);
	}

	/** 세션에 있으면 돌려주고 지운다 (한 번만 쓸 수 있다) */
	static KakaoWithdrawalIntent take(HttpServletRequest request) {
		KakaoWithdrawalIntent intent = peek(request);
		if (intent != null) {
			request.getSession().removeAttribute(SESSION_ATTRIBUTE);
		}
		return intent;
	}

	static KakaoWithdrawalIntent peek(HttpServletRequest request) {
		HttpSession session = request.getSession(false);
		return session != null && session.getAttribute(SESSION_ATTRIBUTE) instanceof KakaoWithdrawalIntent intent
				? intent
				: null;
	}

}
