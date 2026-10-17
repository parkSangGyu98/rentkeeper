package com.rentkeeper.auth.withdrawal;

import java.io.IOException;
import java.time.Clock;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import com.rentkeeper.auth.security.LandlordPrincipal;
import com.rentkeeper.auth.web.AuthenticatedSessionHelper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 카카오 재로그인 콜백에서 탈퇴를 마무리한다 (M1-c 결정 2·12). 세션에 탈퇴 의사 확인이 없으면 아무것도 하지 않고 보통 로그인으로
 * 이어진다.
 * <ul>
 * <li>같은 계정 + 10분 안: 바로 탈퇴하고 로그아웃 → /withdrawn</li>
 * <li>같은 계정이지만 10분 지남: 탈퇴하지 않고 탈퇴 화면으로 (다시 확인)</li>
 * <li>다른 카카오 계정: 탈퇴하지 않는다. 로그인 상태가 다른 계정으로 바뀌었으므로 로그아웃하고 다시 로그인하게 한다</li>
 * <li>카카오에서 취소·실패: 탈퇴 화면으로 (기존 로그인은 그대로)</li>
 * </ul>
 */
@Component
public class KakaoWithdrawalCallback {

	private final WithdrawalService withdrawalService;

	private final AuthenticatedSessionHelper sessionHelper;

	private final Clock clock;

	public KakaoWithdrawalCallback(WithdrawalService withdrawalService, AuthenticatedSessionHelper sessionHelper,
			Clock clock) {
		this.withdrawalService = withdrawalService;
		this.sessionHelper = sessionHelper;
		this.clock = clock;
	}

	/** @return 탈퇴 흐름으로 처리했으면 true (응답을 이미 보냄) */
	public boolean onSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication)
			throws IOException {
		KakaoWithdrawalIntent intent = KakaoWithdrawalIntent.take(request);
		if (intent == null) {
			return false;
		}
		boolean sameAccount = authentication.getPrincipal() instanceof LandlordPrincipal principal
				&& principal.getLandlordId() == intent.landlordId();
		if (!sameAccount) {
			sessionHelper.logout(request, response);
			response.sendRedirect(request.getContextPath() + "/login?withdrawMismatch");
			return true;
		}
		if (!intent.isValidAt(clock.instant())) {
			response.sendRedirect(request.getContextPath() + "/app/settings/withdraw?expired");
			return true;
		}
		withdrawalService.withdrawKakao(intent.landlordId());
		sessionHelper.logout(request, response);
		response.sendRedirect(request.getContextPath() + WithdrawalController.WITHDRAWN_PATH);
		return true;
	}

	/** @return 탈퇴 흐름으로 처리했으면 true */
	public boolean onFailure(HttpServletRequest request, HttpServletResponse response) throws IOException {
		if (KakaoWithdrawalIntent.take(request) == null) {
			return false;
		}
		response.sendRedirect(request.getContextPath() + "/app/settings/withdraw?kakaoError");
		return true;
	}

}
