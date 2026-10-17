package com.rentkeeper.auth.withdrawal;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.rentkeeper.auth.lock.LoginLockService;
import com.rentkeeper.auth.mail.AccountEmails;
import com.rentkeeper.auth.session.AccountSessionTerminator;
import com.rentkeeper.auth.settings.CurrentPasswordVerifier;
import com.rentkeeper.auth.token.AccountTokenService;
import com.rentkeeper.common.mail.EmailDispatcher;
import com.rentkeeper.landlord.application.LandlordAccount;
import com.rentkeeper.landlord.application.LandlordService;
import com.rentkeeper.landlord.domain.LandlordStatus;

/**
 * 회원 탈퇴 (AUTH-5, ADR-015). 재인증을 마친 뒤 바로 처리한다.
 * <ol>
 * <li>계정을 탈퇴 상태로 바꾼다 (이메일·카카오 로그인 조회는 ACTIVE만 찾으므로 바로 로그인할 수 없다)</li>
 * <li>모든 기기의 세션과 로그인 유지(remember-me)를 지운다</li>
 * <li>메일로 나간 링크(인증·재설정 등)와 로그인 실패 기록을 지운다</li>
 * <li>인증된 이메일이 있으면 탈퇴 안내 메일 (커밋 뒤)</li>
 * <li>카카오 계정은 커밋 뒤 카카오 연결 끊기. 성공하면 시각을 남기고, 실패하면 대기 행(kakao_unlink_pending)을
 * 만든다</li>
 * </ol>
 * 개인정보는 30일 뒤 파기 배치가 행째 지운다.
 */
@Service
public class WithdrawalService {

	private static final Logger log = LoggerFactory.getLogger(WithdrawalService.class);

	public enum Failure {

		/** 비밀번호를 여러 번 틀려 잠김 (15분) */
		LOCKED,

		/** 비밀번호가 틀림 */
		WRONG_PASSWORD

	}

	public static class WithdrawalException extends RuntimeException {

		private final Failure failure;

		WithdrawalException(Failure failure) {
			super("withdrawal rejected: " + failure);
			this.failure = failure;
		}

		public Failure getFailure() {
			return failure;
		}

	}

	private final LandlordService landlordService;

	private final CurrentPasswordVerifier currentPasswordVerifier;

	private final AccountSessionTerminator sessionTerminator;

	private final AccountTokenService tokenService;

	private final LoginLockService lockService;

	private final AccountEmails accountEmails;

	private final EmailDispatcher emailDispatcher;

	private final KakaoUnlinker kakaoUnlinker;

	public WithdrawalService(LandlordService landlordService, CurrentPasswordVerifier currentPasswordVerifier,
			AccountSessionTerminator sessionTerminator, AccountTokenService tokenService, LoginLockService lockService,
			AccountEmails accountEmails, EmailDispatcher emailDispatcher, KakaoUnlinker kakaoUnlinker) {
		this.landlordService = landlordService;
		this.currentPasswordVerifier = currentPasswordVerifier;
		this.sessionTerminator = sessionTerminator;
		this.tokenService = tokenService;
		this.lockService = lockService;
		this.accountEmails = accountEmails;
		this.emailDispatcher = emailDispatcher;
		this.kakaoUnlinker = kakaoUnlinker;
	}

	/**
	 * 이메일 가입 계정 탈퇴: 지금 비밀번호로 재인증한다. 틀리면 로그인 실패와 같은 횟수에 더한다 (ADR-012).
	 *
	 * @throws WithdrawalException
	 *             잠김·비밀번호 틀림
	 */
	@Transactional
	public void withdrawLocal(long landlordId, String currentPassword) {
		LandlordAccount account = landlordService.findAccount(landlordId).orElseThrow();
		if (account.isKakao()) {
			throw new IllegalStateException("KAKAO accounts re-authenticate with Kakao");
		}
		switch (currentPasswordVerifier.verify(landlordId, currentPassword)) {
			case LOCKED -> throw new WithdrawalException(Failure.LOCKED);
			case WRONG -> throw new WithdrawalException(Failure.WRONG_PASSWORD);
			case OK -> {
			}
		}
		withdraw(account);
	}

	/** 카카오 계정 탈퇴. 호출하는 쪽이 카카오 재로그인으로 같은 계정임을 확인한 뒤 부른다 (KakaoWithdrawalHandler) */
	@Transactional
	public void withdrawKakao(long landlordId) {
		LandlordAccount account = landlordService.findAccount(landlordId).orElseThrow();
		if (!account.isKakao()) {
			throw new IllegalStateException("LOCAL accounts re-authenticate with password");
		}
		Instant withdrawnAt = withdraw(account);
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				// 탈퇴는 이미 확정됐다. 카카오 쪽이 실패해도 탈퇴를 되돌리지 않고, 대기 행이 남아 파기 배치가 다시 시도한다
				try {
					kakaoUnlinker.attemptAfterWithdrawal(landlordId, account.kakaoId(), withdrawnAt);
				} catch (RuntimeException e) {
					log.warn("kakao unlink after withdrawal failed: landlordId={}, error={}", landlordId,
							e.getClass().getSimpleName());
				}
			}
		});
	}

	/** @return 탈퇴 시각 */
	private Instant withdraw(LandlordAccount account) {
		if (account.status() != LandlordStatus.ACTIVE) {
			throw new IllegalStateException("already withdrawn");
		}
		long landlordId = account.id();
		// 상태를 바꾸기 전에 안내 메일 주소를 얻는다 (알림은 인증된 주소로만)
		var verifiedEmail = landlordService.findVerifiedEmail(landlordId);

		Instant withdrawnAt = landlordService.withdraw(landlordId);
		sessionTerminator.terminateAll(landlordId);
		tokenService.deleteAll(landlordId);
		lockService.clear(landlordId);
		verifiedEmail.ifPresent(
				email -> emailDispatcher.sendAfterCommit(accountEmails.withdrawn(email.value(), account.name())));
		log.info("landlord withdrawn: id={}", landlordId);
		return withdrawnAt;
	}

}
