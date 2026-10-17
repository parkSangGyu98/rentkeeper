package com.rentkeeper.auth.token;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.rentkeeper.common.crypto.SecureTokens;

/**
 * 1회용 계정 토큰 발급·확인·사용 (ADR-011).
 * <ul>
 * <li>발급: 원문은 돌려주기만 하고(메일 링크용) DB에는 SHA-256만 저장한다. 같은 계정·용도의 이전 토큰은 무효가
 * 된다.</li>
 * <li>확인({@link #findUsable}): 링크를 연 화면(GET)에서 쓴다. 토큰을 소비하지 않는다.</li>
 * <li>사용({@link #consume}): 버튼을 눌러 제출(POST)할 때 쓴다. 한 번만 성공한다.</li>
 * </ul>
 * 메일 보안 검사기가 링크를 미리 열어 봐도(GET) 토큰이 사라지지 않게 하려고 확인과 사용을 나눴다.
 */
@Service
@Transactional(readOnly = true)
public class AccountTokenService {

	private final AccountTokenRepository repository;

	private final Clock clock;

	public AccountTokenService(AccountTokenRepository repository, Clock clock) {
		this.repository = repository;
		this.clock = clock;
	}

	public record IssuedToken(String rawToken, Instant expiresAt) {

		// 원문 토큰은 로그에 남지 않게 한다
		@Override
		public String toString() {
			return "IssuedToken[expiresAt=" + expiresAt + "]";
		}

	}

	public record UsableToken(long landlordId, TokenPurpose purpose, String targetEmail) {
	}

	@Transactional
	public IssuedToken issue(long landlordId, TokenPurpose purpose, String targetEmail) {
		Instant now = clock.instant();
		repository.invalidateUsable(landlordId, purpose, now);
		String raw = SecureTokens.newToken();
		Instant expiresAt = now.plus(purpose.validity());
		repository.save(new AccountToken(landlordId, purpose, SecureTokens.sha256Hex(raw), targetEmail, expiresAt));
		return new IssuedToken(raw, expiresAt);
	}

	/** 링크가 아직 쓸 수 있는지 확인만 한다 (소비하지 않음) */
	public Optional<UsableToken> findUsable(String rawToken, TokenPurpose purpose) {
		Instant now = clock.instant();
		return find(rawToken, purpose).filter(token -> token.isUsableAt(now)).map(AccountTokenService::toUsable);
	}

	/**
	 * 토큰을 사용 처리한다. 이미 쓰였거나 만료됐거나 용도가 다르면 빈 값. 호출한 쪽의 트랜잭션에 참여하므로, 이후 처리(비밀번호 변경 등)가
	 * 실패해 롤백되면 토큰 사용도 함께 취소된다.
	 */
	@Transactional
	public Optional<UsableToken> consume(String rawToken, TokenPurpose purpose) {
		Optional<AccountToken> token = find(rawToken, purpose);
		if (token.isEmpty() || repository.consume(token.get().getId(), clock.instant()) != 1) {
			return Optional.empty();
		}
		return token.map(AccountTokenService::toUsable);
	}

	private Optional<AccountToken> find(String rawToken, TokenPurpose purpose) {
		if (!SecureTokens.isWellFormed(rawToken)) {
			return Optional.empty();
		}
		return repository.findByTokenHash(SecureTokens.sha256Hex(rawToken)).filter(t -> t.getPurpose() == purpose);
	}

	private static UsableToken toUsable(AccountToken token) {
		return new UsableToken(token.getLandlordId(), token.getPurpose(), token.getTargetEmail());
	}

}
