package com.rentkeeper.auth.reset;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.rentkeeper.auth.lock.LoginLockService;
import com.rentkeeper.auth.mail.AccountEmails;
import com.rentkeeper.auth.session.AccountSessionTerminator;
import com.rentkeeper.auth.signup.PasswordPolicy;
import com.rentkeeper.auth.signup.WeakPasswordException;
import com.rentkeeper.auth.token.AccountTokenService;
import com.rentkeeper.auth.token.AccountTokenService.IssuedToken;
import com.rentkeeper.auth.token.AccountTokenService.UsableToken;
import com.rentkeeper.auth.token.TokenPurpose;
import com.rentkeeper.common.crypto.SecureTokens;
import com.rentkeeper.common.mail.EmailDispatcher;
import com.rentkeeper.common.ratelimit.RateLimitRule;
import com.rentkeeper.common.ratelimit.RateLimiter;
import com.rentkeeper.landlord.application.LandlordAccount;
import com.rentkeeper.landlord.application.LandlordService;
import com.rentkeeper.landlord.domain.EmailAddress;

/**
 * 비밀번호 재설정 (AUTH-3): 이메일 링크, 30분, 1회용, 토큰은 해시로 저장 (ADR-011).
 * <p>
 * 요청 단계는 가입 여부를 드러내지 않는다: 없는 이메일·형식이 틀린 이메일·한도 초과 모두 호출한 쪽에는 똑같이 "끝났다"로 보인다.
 */
@Service
public class PasswordResetService {

	private final LandlordService landlordService;

	private final AccountTokenService tokenService;

	private final AccountEmails accountEmails;

	private final EmailDispatcher emailDispatcher;

	private final RateLimiter rateLimiter;

	private final PasswordPolicy passwordPolicy;

	private final PasswordEncoder passwordEncoder;

	private final LoginLockService lockService;

	private final AccountSessionTerminator sessionTerminator;

	public PasswordResetService(LandlordService landlordService, AccountTokenService tokenService,
			AccountEmails accountEmails, EmailDispatcher emailDispatcher, RateLimiter rateLimiter,
			PasswordPolicy passwordPolicy, PasswordEncoder passwordEncoder, LoginLockService lockService,
			AccountSessionTerminator sessionTerminator) {
		this.landlordService = landlordService;
		this.tokenService = tokenService;
		this.accountEmails = accountEmails;
		this.emailDispatcher = emailDispatcher;
		this.rateLimiter = rateLimiter;
		this.passwordPolicy = passwordPolicy;
		this.passwordEncoder = passwordEncoder;
		this.lockService = lockService;
		this.sessionTerminator = sessionTerminator;
	}

	/**
	 * 재설정 메일을 보낸다(조건이 맞을 때만). 결과를 돌려주지 않는다: 화면은 항상 같은 안내를 보여 준다.
	 * <p>
	 * 이메일당 1시간 3회 한도를 넘으면 조용히 보내지 않는다. 이 한도는 남의 재설정 기회를 소진시킬 수 있는 한계가 있다 (ADR-012
	 * 알려진 한계 1).
	 */
	@Transactional
	public void requestReset(String rawEmail) {
		EmailAddress email;
		try {
			email = EmailAddress.of(rawEmail);
		} catch (EmailAddress.InvalidEmailException e) {
			return;
		}
		// 키에 이메일 원문 대신 해시를 쓴다 (카운터 테이블에 개인정보를 남기지 않게)
		if (!rateLimiter.tryAcquire(RateLimitRule.PASSWORD_RESET_REQUEST_PER_EMAIL,
				SecureTokens.sha256Hex(email.value()))) {
			return;
		}
		landlordService.findLocalCredentials(email).ifPresent(account -> {
			IssuedToken token = tokenService.issue(account.id(), TokenPurpose.PASSWORD_RESET, null);
			// 본인 확인용 메일이라 아직 인증되지 않은 주소에도 보낸다
			emailDispatcher
					.sendAfterCommit(accountEmails.passwordReset(account.email(), account.name(), token.rawToken()));
		});
	}

	public boolean isUsable(String rawToken) {
		return tokenService.findUsable(rawToken, TokenPurpose.PASSWORD_RESET).isPresent();
	}

	/**
	 * 새 비밀번호로 바꾼다. 성공하면 모든 기기에서 로그아웃시키고(세션 + remember-me), 로그인 잠금을 풀고, 이메일을 인증
	 * 처리한다(링크를 연 것으로 메일함의 주인임이 확인됐으므로).
	 *
	 * @return 토큰이 만료·사용됨·잘못됐으면 false
	 * @throws WeakPasswordException
	 *             새 비밀번호가 규칙에 맞지 않을 때 (토큰은 소비되지 않아 다시 시도할 수 있다)
	 */
	@Transactional
	public boolean reset(String rawToken, String newPassword) {
		UsableToken usable = tokenService.findUsable(rawToken, TokenPurpose.PASSWORD_RESET).orElse(null);
		if (usable == null) {
			return false;
		}
		LandlordAccount account = landlordService.findAccount(usable.landlordId()).orElse(null);
		if (account == null) {
			return false;
		}
		EmailAddress email = new EmailAddress(account.email());
		passwordPolicy.check(newPassword, email).ifPresent(violation -> {
			throw new WeakPasswordException(violation);
		});
		if (tokenService.consume(rawToken, TokenPurpose.PASSWORD_RESET).isEmpty()) {
			return false; // 그 사이 다른 요청이 먼저 썼다
		}

		long landlordId = account.id();
		landlordService.changePassword(landlordId, passwordEncoder.encode(newPassword));
		landlordService.verifyEmail(landlordId, email);
		lockService.clear(landlordId);
		sessionTerminator.terminateAll(landlordId);
		// 보안 안내는 인증된 주소로만 (M1-b 결정 8). 위에서 인증 처리했으므로 보통 보내진다
		landlordService.findVerifiedEmail(landlordId).ifPresent(verified -> emailDispatcher
				.sendAfterCommit(accountEmails.passwordChanged(verified.value(), account.name())));
		return true;
	}

}
