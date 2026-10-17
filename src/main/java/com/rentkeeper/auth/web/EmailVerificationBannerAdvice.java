package com.rentkeeper.auth.web;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import com.rentkeeper.auth.security.LandlordPrincipal;
import com.rentkeeper.landlord.application.LandlordAccount;
import com.rentkeeper.landlord.application.LandlordService;

/**
 * 로그인한 사용자의 이메일이 아직 인증되지 않았으면 모든 화면 위쪽에 안내 띠를 보여 준다 (레이아웃의
 * emailVerificationPending).
 * <p>
 * 세션에 저장된 로그인 정보가 아니라 매번 DB를 본다. 다른 기기에서 인증을 마쳐도 이 기기의 안내 띠가 바로 사라지게 하기 위해서다.
 */
@ControllerAdvice
public class EmailVerificationBannerAdvice {

	private final LandlordService landlordService;

	public EmailVerificationBannerAdvice(LandlordService landlordService) {
		this.landlordService = landlordService;
	}

	@ModelAttribute("emailVerificationPending")
	public boolean emailVerificationPending(Authentication authentication) {
		if (authentication == null || !(authentication.getPrincipal() instanceof LandlordPrincipal principal)) {
			return false;
		}
		return landlordService.findAccount(principal.getLandlordId()).map(LandlordAccount::emailVerified)
				.map(verified -> !verified).orElse(false);
	}

}
