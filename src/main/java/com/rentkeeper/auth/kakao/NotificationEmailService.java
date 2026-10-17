package com.rentkeeper.auth.kakao;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.rentkeeper.auth.verification.EmailVerificationService;
import com.rentkeeper.landlord.application.LandlordAccount;
import com.rentkeeper.landlord.application.LandlordService;
import com.rentkeeper.landlord.domain.EmailAddress;

/**
 * 카카오 계정의 알림 받을 이메일을 넣거나 고친다 (M1-c 결정 7·11). 아직 인증되지 않은 주소일 때만 할 수 있고, 인증된 주소를
 * 바꾸는 기능은 P1이다.
 */
@Service
public class NotificationEmailService {

	private final LandlordService landlordService;

	private final EmailVerificationService emailVerificationService;

	public NotificationEmailService(LandlordService landlordService,
			EmailVerificationService emailVerificationService) {
		this.landlordService = landlordService;
		this.emailVerificationService = emailVerificationService;
	}

	/** 이 화면을 쓸 수 있는 계정인지: 카카오 계정이고 이메일이 없거나 인증 전 */
	public boolean isEditable(long landlordId) {
		return landlordService.findAccount(landlordId).filter(LandlordAccount::isKakao)
				.filter(account -> !account.emailVerified()).isPresent();
	}

	/** 주소를 저장하고 인증 메일을 보낸다. 예전 주소로 보낸 인증 링크는 쓸 수 없게 된다 */
	@Transactional
	public void set(long landlordId, EmailAddress email) {
		landlordService.setUnverifiedNotificationEmail(landlordId, email);
		emailVerificationService.sendVerification(landlordId);
	}

}
