package com.rentkeeper.auth.web;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import com.rentkeeper.auth.security.LandlordPrincipal;
import com.rentkeeper.landlord.application.LandlordAccount;
import com.rentkeeper.landlord.application.LandlordService;

/**
 * 로그인한 사용자의 이메일이 없거나 아직 인증되지 않았으면 모든 화면 위쪽에 안내 띠를 보여 준다 (레이아웃의 emailBanner).
 * <p>
 * 세션에 저장된 로그인 정보가 아니라 매번 DB를 본다. 다른 기기에서 인증을 마쳐도 이 기기의 안내 띠가 바로 사라지게 하기 위해서다.
 */
@ControllerAdvice
public class EmailVerificationBannerAdvice {

	private final LandlordService landlordService;

	public EmailVerificationBannerAdvice(LandlordService landlordService) {
		this.landlordService = landlordService;
	}

	/** 안내할 것이 없으면 null */
	@ModelAttribute("emailBanner")
	public EmailBanner emailBanner(Authentication authentication) {
		if (authentication == null || !(authentication.getPrincipal() instanceof LandlordPrincipal principal)) {
			return null;
		}
		return landlordService.findAccount(principal.getLandlordId()).map(EmailBanner::of).orElse(null);
	}

	/**
	 * @param missing
	 *            알림 받을 이메일이 없음 (카카오 가입 때 "나중에 하기")
	 * @param unverified
	 *            이메일은 있지만 인증 전
	 * @param editable
	 *            안내 띠에서 주소를 고칠 수 있음 (카카오 계정만, M1-c 결정 11)
	 */
	public record EmailBanner(boolean missing, boolean unverified, boolean editable) {

		static EmailBanner of(LandlordAccount account) {
			if (account.emailVerified()) {
				return null;
			}
			boolean missing = account.email() == null;
			return new EmailBanner(missing, !missing, account.isKakao());
		}

	}

}
