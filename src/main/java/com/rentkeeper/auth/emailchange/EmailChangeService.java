package com.rentkeeper.auth.emailchange;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.rentkeeper.auth.mail.AccountEmails;
import com.rentkeeper.auth.settings.CurrentPasswordVerifier;
import com.rentkeeper.auth.token.AccountTokenService;
import com.rentkeeper.auth.token.AccountTokenService.IssuedToken;
import com.rentkeeper.auth.token.AccountTokenService.UsableToken;
import com.rentkeeper.auth.token.TokenPurpose;
import com.rentkeeper.common.mail.EmailDispatcher;
import com.rentkeeper.landlord.application.DuplicateEmailException;
import com.rentkeeper.landlord.application.LandlordAccount;
import com.rentkeeper.landlord.application.LandlordService;
import com.rentkeeper.landlord.application.LandlordService.EmailChanged;
import com.rentkeeper.landlord.domain.EmailAddress;

/**
 * 로그인 이메일 바꾸기 (M1-b 결정 7: 새 주소 인증 후 반영).
 * <ol>
 * <li>요청: 지금 비밀번호 재확인 → 새 주소로 확인 링크(24시간)를 보낸다. 이 시점에는 이메일이 바뀌지 않는다.</li>
 * <li>확인: 새 주소의 링크에서 버튼을 누르면 그때 바뀌고, 새 주소는 인증된 것으로 표시된다.</li>
 * </ol>
 * 주소를 잘못 입력해도 로그인 ID를 잃지 않게 하기 위해 확인 후에 바꾼다.
 */
@Service
public class EmailChangeService {

	public enum Failure {

		LOCKED, WRONG_CURRENT_PASSWORD, INVALID_EMAIL, SAME_AS_CURRENT, ALREADY_TAKEN

	}

	public static class EmailChangeException extends RuntimeException {

		private final Failure failure;

		EmailChangeException(Failure failure) {
			super("email change rejected: " + failure);
			this.failure = failure;
		}

		public Failure getFailure() {
			return failure;
		}

	}

	private final LandlordService landlordService;

	private final CurrentPasswordVerifier currentPasswordVerifier;

	private final AccountTokenService tokenService;

	private final AccountEmails accountEmails;

	private final EmailDispatcher emailDispatcher;

	public EmailChangeService(LandlordService landlordService, CurrentPasswordVerifier currentPasswordVerifier,
			AccountTokenService tokenService, AccountEmails accountEmails, EmailDispatcher emailDispatcher) {
		this.landlordService = landlordService;
		this.currentPasswordVerifier = currentPasswordVerifier;
		this.tokenService = tokenService;
		this.accountEmails = accountEmails;
		this.emailDispatcher = emailDispatcher;
	}

	/**
	 * 새 주소로 확인 메일을 보낸다. 이전에 보낸 변경 링크는 쓸 수 없게 된다.
	 *
	 * @throws EmailChangeException
	 *             재인증 실패, 주소 형식 오류, 지금 주소와 같음, 다른 계정이 쓰는 주소
	 */
	@Transactional
	public void requestChange(long landlordId, String currentPassword, String rawNewEmail) {
		switch (currentPasswordVerifier.verify(landlordId, currentPassword)) {
			case LOCKED -> throw new EmailChangeException(Failure.LOCKED);
			case WRONG -> throw new EmailChangeException(Failure.WRONG_CURRENT_PASSWORD);
			case OK -> {
			}
		}
		EmailAddress newEmail;
		try {
			newEmail = EmailAddress.of(rawNewEmail);
		} catch (EmailAddress.InvalidEmailException e) {
			throw new EmailChangeException(Failure.INVALID_EMAIL);
		}
		LandlordAccount account = landlordService.findAccount(landlordId).orElseThrow();
		if (newEmail.value().equals(account.email())) {
			throw new EmailChangeException(Failure.SAME_AS_CURRENT);
		}
		if (landlordService.isLocalEmailTaken(newEmail)) {
			throw new EmailChangeException(Failure.ALREADY_TAKEN);
		}
		IssuedToken token = tokenService.issue(landlordId, TokenPurpose.EMAIL_CHANGE, newEmail.value());
		emailDispatcher.sendAfterCommit(
				accountEmails.emailChangeConfirmation(newEmail.value(), account.name(), token.rawToken()));
	}

	public boolean isUsable(String rawToken) {
		return tokenService.findUsable(rawToken, TokenPurpose.EMAIL_CHANGE).isPresent();
	}

	/**
	 * 링크의 토큰으로 이메일을 바꾼다. 이전 주소가 인증돼 있었으면 이전 주소로 "바뀌었어요" 안내를 보낸다 (M1-b 결정 8).
	 *
	 * @return 바꿨으면 true, 링크가 만료·사용됨·잘못됐으면 false
	 * @throws DuplicateEmailException
	 *             요청 뒤에 다른 계정이 같은 주소로 가입한 경우 (트랜잭션 전체가 롤백되어 토큰도 쓰지 않은 상태로 남는다)
	 */
	@Transactional
	public boolean confirm(String rawToken) {
		UsableToken token = tokenService.consume(rawToken, TokenPurpose.EMAIL_CHANGE).orElse(null);
		if (token == null) {
			return false;
		}
		LandlordAccount account = landlordService.findAccount(token.landlordId()).orElseThrow();
		EmailChanged changed = landlordService.changeEmail(token.landlordId(), new EmailAddress(token.targetEmail()));
		if (changed.previousVerified()) {
			emailDispatcher.sendAfterCommit(
					accountEmails.emailChanged(changed.previousEmail(), account.name(), token.targetEmail()));
		}
		return true;
	}

}
