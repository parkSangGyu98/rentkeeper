package com.rentkeeper.auth.verification;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.rentkeeper.auth.mail.AccountEmails;
import com.rentkeeper.auth.token.AccountTokenService;
import com.rentkeeper.auth.token.AccountTokenService.IssuedToken;
import com.rentkeeper.auth.token.AccountTokenService.UsableToken;
import com.rentkeeper.auth.token.TokenPurpose;
import com.rentkeeper.common.mail.EmailDispatcher;
import com.rentkeeper.landlord.application.LandlordAccount;
import com.rentkeeper.landlord.application.LandlordService;
import com.rentkeeper.landlord.domain.EmailAddress;

/**
 * 가입한 이메일 인증. 인증 전에도 로그인은 되고(배너로 안내), 알림은 인증된 주소로만 보낸다 (M1-b).
 */
@Service
public class EmailVerificationService {

	private final LandlordService landlordService;

	private final AccountTokenService tokenService;

	private final AccountEmails accountEmails;

	private final EmailDispatcher emailDispatcher;

	public EmailVerificationService(LandlordService landlordService, AccountTokenService tokenService,
			AccountEmails accountEmails, EmailDispatcher emailDispatcher) {
		this.landlordService = landlordService;
		this.tokenService = tokenService;
		this.accountEmails = accountEmails;
		this.emailDispatcher = emailDispatcher;
	}

	/**
	 * 인증 메일을 보낸다. 이전에 보낸 인증 링크는 쓸 수 없게 된다. 인증 링크는 본인 확인용이라 아직 인증되지 않은 주소로 보낸다.
	 *
	 * @return 보냈으면 true, 이미 인증됐거나 이메일이 없으면 false
	 */
	@Transactional
	public boolean sendVerification(long landlordId) {
		LandlordAccount account = landlordService.findAccount(landlordId).orElse(null);
		if (account == null || account.email() == null || account.emailVerified()) {
			return false;
		}
		IssuedToken token = tokenService.issue(landlordId, TokenPurpose.EMAIL_VERIFICATION, account.email());
		emailDispatcher
				.sendAfterCommit(accountEmails.emailVerification(account.email(), account.name(), token.rawToken()));
		return true;
	}

	/** 링크가 아직 쓸 수 있는지 (화면 표시용, 소비하지 않음) */
	public boolean isUsable(String rawToken) {
		return tokenService.findUsable(rawToken, TokenPurpose.EMAIL_VERIFICATION).isPresent();
	}

	/**
	 * 링크의 토큰으로 인증을 완료한다.
	 *
	 * @return 인증됐으면 true. 만료·사용됨·잘못된 링크이거나, 메일을 보낸 뒤 이메일이 바뀌었으면 false
	 */
	@Transactional
	public boolean verify(String rawToken) {
		return tokenService.consume(rawToken, TokenPurpose.EMAIL_VERIFICATION)
				.map(token -> landlordService.verifyEmail(token.landlordId(), toAddress(token))).orElse(false);
	}

	private static EmailAddress toAddress(UsableToken token) {
		return new EmailAddress(token.targetEmail());
	}

}
