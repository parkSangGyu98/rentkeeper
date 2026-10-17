package com.rentkeeper.auth.settings;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.rentkeeper.auth.lock.LoginLockService;
import com.rentkeeper.auth.mail.AccountEmails;
import com.rentkeeper.auth.session.AccountSessionTerminator;
import com.rentkeeper.auth.signup.PasswordPolicy;
import com.rentkeeper.auth.signup.WeakPasswordException;
import com.rentkeeper.common.mail.EmailDispatcher;
import com.rentkeeper.landlord.application.LandlordAccount;
import com.rentkeeper.landlord.application.LandlordService;
import com.rentkeeper.landlord.domain.EmailAddress;

/**
 * 로그인한 상태에서 비밀번호 바꾸기 (민감 작업 재인증, M1-b).
 * <p>
 * 로그인 방식(비밀번호·remember-me)과 관계없이 <strong>항상 현재 비밀번호</strong>를 다시 확인한다. 현재 비밀번호를
 * 틀리면 로그인 실패와 같은 횟수에 더한다: 세션을 가로챈 사람이 이 화면에서 비밀번호를 맞춰 보는 것을 막기 위해서다 (ADR-012).
 */
@Service
public class PasswordChangeService {

	private final LandlordService landlordService;

	private final PasswordEncoder passwordEncoder;

	private final PasswordPolicy passwordPolicy;

	private final LoginLockService lockService;

	private final AccountSessionTerminator sessionTerminator;

	private final AccountEmails accountEmails;

	private final EmailDispatcher emailDispatcher;

	public PasswordChangeService(LandlordService landlordService, PasswordEncoder passwordEncoder,
			PasswordPolicy passwordPolicy, LoginLockService lockService, AccountSessionTerminator sessionTerminator,
			AccountEmails accountEmails, EmailDispatcher emailDispatcher) {
		this.landlordService = landlordService;
		this.passwordEncoder = passwordEncoder;
		this.passwordPolicy = passwordPolicy;
		this.lockService = lockService;
		this.sessionTerminator = sessionTerminator;
		this.accountEmails = accountEmails;
		this.emailDispatcher = emailDispatcher;
	}

	public enum Failure {

		/** 현재 비밀번호를 여러 번 틀려 잠김 (15분) */
		LOCKED,

		/** 현재 비밀번호가 틀림 */
		WRONG_CURRENT_PASSWORD,

		/** 새 비밀번호가 지금 비밀번호와 같음 */
		SAME_AS_CURRENT

	}

	public static class PasswordChangeException extends RuntimeException {

		private final Failure failure;

		PasswordChangeException(Failure failure) {
			super("password change rejected: " + failure);
			this.failure = failure;
		}

		public Failure getFailure() {
			return failure;
		}

	}

	/**
	 * 비밀번호를 바꾸고, 지금 쓰는 세션만 남기고 다른 기기는 모두 로그아웃시킨다 (remember-me 포함).
	 *
	 * @param currentSessionId
	 *            남길 세션 (지금 이 요청의 세션)
	 * @throws PasswordChangeException
	 *             잠김·현재 비밀번호 틀림·새 비밀번호가 지금과 같음
	 * @throws WeakPasswordException
	 *             새 비밀번호가 규칙에 맞지 않음
	 */
	@Transactional
	public void change(long landlordId, String currentPassword, String newPassword, String currentSessionId) {
		LandlordAccount account = landlordService.findAccount(landlordId).orElseThrow();
		String currentHash = landlordService.findPasswordHash(landlordId).orElseThrow();
		// 잠겨 있어도 비밀번호 비교는 먼저 한다 (로그인과 같은 순서)
		boolean currentMatches = passwordEncoder.matches(currentPassword, currentHash);
		if (lockService.isLocked(landlordId)) {
			throw new PasswordChangeException(Failure.LOCKED);
		}
		if (!currentMatches) {
			lockService.recordFailure(landlordId);
			throw new PasswordChangeException(Failure.WRONG_CURRENT_PASSWORD);
		}
		if (currentPassword.equals(newPassword)) {
			throw new PasswordChangeException(Failure.SAME_AS_CURRENT);
		}
		passwordPolicy.check(newPassword, new EmailAddress(account.email())).ifPresent(violation -> {
			throw new WeakPasswordException(violation);
		});

		landlordService.changePassword(landlordId, passwordEncoder.encode(newPassword));
		lockService.clear(landlordId);
		sessionTerminator.terminateAllExcept(landlordId, currentSessionId);
		// 보안 안내는 인증된 주소로만 (M1-b 결정 8)
		landlordService.findVerifiedEmail(landlordId).ifPresent(verified -> emailDispatcher
				.sendAfterCommit(accountEmails.passwordChanged(verified.value(), account.name())));
	}

}
