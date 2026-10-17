package com.rentkeeper.auth.kakao;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.rentkeeper.auth.security.LandlordPrincipal;
import com.rentkeeper.auth.verification.EmailVerificationService;
import com.rentkeeper.consent.ConsentService;
import com.rentkeeper.landlord.application.LandlordService;
import com.rentkeeper.landlord.domain.EmailAddress;

/**
 * 카카오로 처음 온 사람의 가입을 마친다 (ADR-014): 계정 생성 + 동의 기록 + (필요하면) 인증 메일을 한 트랜잭션으로.
 */
@Service
public class KakaoSignupService {

	private final LandlordService landlordService;

	private final ConsentService consentService;

	private final EmailVerificationService emailVerificationService;

	public KakaoSignupService(LandlordService landlordService, ConsentService consentService,
			EmailVerificationService emailVerificationService) {
		this.landlordService = landlordService;
		this.consentService = consentService;
		this.emailVerificationService = emailVerificationService;
	}

	/**
	 * @param rawNotificationEmail
	 *            알림 받을 이메일. 비어 있으면 "나중에 하기"(M1-c 결정 7)
	 * @throws EmailAddress.InvalidEmailException
	 *             이메일 형식이 잘못됐을 때
	 * @throws com.rentkeeper.landlord.domain.Landlord.InvalidNameException
	 *             이름이 비었거나 너무 길 때
	 * @throws com.rentkeeper.landlord.application.DuplicateKakaoAccountException
	 *             다른 창에서 이미 가입을 마쳤을 때
	 */
	@Transactional
	public LandlordPrincipal complete(KakaoProfile profile, String name, String rawNotificationEmail) {
		EmailAddress email = rawNotificationEmail == null || rawNotificationEmail.isBlank()
				? null
				: EmailAddress.of(rawNotificationEmail);
		// 카카오가 인증한 주소를 그대로 쓸 때만 인증된 것으로 저장한다. 사용자가 다른 주소로 바꿨으면 우리 인증 메일로 확인 (결정 5)
		boolean trusted = email != null && profile.emailTrusted() && email.value().equals(profile.email());

		long landlordId = landlordService.registerKakao(profile.kakaoId(), name, email, trusted);
		consentService.recordSignupAgreements(landlordId);
		if (email != null && !trusted) {
			emailVerificationService.sendVerification(landlordId);
		}
		return LandlordPrincipal.of(landlordService.findAccount(landlordId).orElseThrow());
	}

}
