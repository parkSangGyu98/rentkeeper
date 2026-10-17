package com.rentkeeper.auth.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.rentkeeper.TestcontainersConfiguration;
import com.rentkeeper.auth.token.AccountTokenService.IssuedToken;
import com.rentkeeper.auth.token.AccountTokenService.UsableToken;
import com.rentkeeper.common.crypto.SecureTokens;
import com.rentkeeper.landlord.application.LandlordService;
import com.rentkeeper.landlord.domain.EmailAddress;
import com.rentkeeper.support.MutableClock;
import com.rentkeeper.support.MutableClockConfiguration;

/**
 * 1회용 계정 토큰: 해시 저장, 만료, 1회 사용, 재발급 시 이전 토큰 무효, 동시 사용, 정리 (ADR-011).
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, MutableClockConfiguration.class})
class AccountTokenServiceTests {

	@Autowired
	AccountTokenService tokenService;

	@Autowired
	AccountTokenCleanupJob cleanupJob;

	@Autowired
	LandlordService landlordService;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	MutableClock clock;

	long landlordId;

	@BeforeEach
	void setUp() {
		clock.set(MutableClockConfiguration.START);
		jdbcTemplate.update("delete from landlord");
		landlordId = landlordService.registerLocal(EmailAddress.of("kim@example.com"), "{bcrypt}hash", "김임대");
	}

	@Test
	void storesOnlyHashOfToken() {
		IssuedToken issued = tokenService.issue(landlordId, TokenPurpose.PASSWORD_RESET, null);

		String stored = jdbcTemplate.queryForObject("select token_hash from account_token", String.class);
		assertThat(stored).isEqualTo(SecureTokens.sha256Hex(issued.rawToken())).matches("[0-9a-f]{64}");
		assertThat(jdbcTemplate.queryForObject("select count(*) from account_token where token_hash = ?", Integer.class,
				issued.rawToken())).isZero();
		assertThat(issued.expiresAt()).isEqualTo(MutableClockConfiguration.START.plus(Duration.ofMinutes(30)));
		assertThat(issued.toString()).doesNotContain(issued.rawToken());
	}

	@Test
	void tokenCanBeConsumedOnlyOnce() {
		String raw = tokenService.issue(landlordId, TokenPurpose.PASSWORD_RESET, null).rawToken();

		assertThat(tokenService.findUsable(raw, TokenPurpose.PASSWORD_RESET)).isPresent();
		assertThat(tokenService.consume(raw, TokenPurpose.PASSWORD_RESET))
				.contains(new UsableToken(landlordId, TokenPurpose.PASSWORD_RESET, null));
		assertThat(tokenService.consume(raw, TokenPurpose.PASSWORD_RESET)).isEmpty();
		assertThat(tokenService.findUsable(raw, TokenPurpose.PASSWORD_RESET)).isEmpty();
	}

	@Test
	void findUsableDoesNotConsume() {
		String raw = tokenService.issue(landlordId, TokenPurpose.PASSWORD_RESET, null).rawToken();

		tokenService.findUsable(raw, TokenPurpose.PASSWORD_RESET);
		tokenService.findUsable(raw, TokenPurpose.PASSWORD_RESET);

		assertThat(tokenService.consume(raw, TokenPurpose.PASSWORD_RESET)).isPresent();
	}

	@Test
	void expiresAfterValidity() {
		String reset = tokenService.issue(landlordId, TokenPurpose.PASSWORD_RESET, null).rawToken();
		String verify = tokenService.issue(landlordId, TokenPurpose.EMAIL_VERIFICATION, "kim@example.com").rawToken();

		clock.advance(Duration.ofMinutes(30));
		assertThat(tokenService.consume(reset, TokenPurpose.PASSWORD_RESET)).as("재설정 30분").isEmpty();
		assertThat(tokenService.findUsable(verify, TokenPurpose.EMAIL_VERIFICATION)).isPresent();

		clock.advance(Duration.ofHours(24));
		assertThat(tokenService.consume(verify, TokenPurpose.EMAIL_VERIFICATION)).as("인증 24시간").isEmpty();
	}

	@Test
	void reissuingInvalidatesPreviousTokenOfSamePurpose() {
		String first = tokenService.issue(landlordId, TokenPurpose.PASSWORD_RESET, null).rawToken();
		String verify = tokenService.issue(landlordId, TokenPurpose.EMAIL_VERIFICATION, "kim@example.com").rawToken();
		String second = tokenService.issue(landlordId, TokenPurpose.PASSWORD_RESET, null).rawToken();

		assertThat(tokenService.consume(first, TokenPurpose.PASSWORD_RESET)).isEmpty();
		assertThat(tokenService.consume(second, TokenPurpose.PASSWORD_RESET)).isPresent();
		assertThat(tokenService.consume(verify, TokenPurpose.EMAIL_VERIFICATION)).as("다른 용도는 그대로").isPresent();
	}

	@Test
	void tokenOfAnotherPurposeOrMalformedValueIsRejected() {
		String raw = tokenService.issue(landlordId, TokenPurpose.PASSWORD_RESET, null).rawToken();

		assertThat(tokenService.consume(raw, TokenPurpose.EMAIL_VERIFICATION)).isEmpty();
		assertThat(tokenService.consume("not-a-token", TokenPurpose.PASSWORD_RESET)).isEmpty();
		assertThat(tokenService.consume(null, TokenPurpose.PASSWORD_RESET)).isEmpty();
		assertThat(tokenService.consume(SecureTokens.newToken(), TokenPurpose.PASSWORD_RESET)).isEmpty();
	}

	@Test
	void concurrentConsumeSucceedsOnlyOnce() throws Exception {
		String raw = tokenService.issue(landlordId, TokenPurpose.PASSWORD_RESET, null).rawToken();
		int threads = 8;
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(threads);
		try {
			List<Future<Boolean>> results = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				Callable<Boolean> task = () -> {
					start.await();
					return tokenService.consume(raw, TokenPurpose.PASSWORD_RESET).isPresent();
				};
				results.add(executor.submit(task));
			}
			start.countDown();
			long successes = 0;
			for (Future<Boolean> result : results) {
				if (result.get()) {
					successes++;
				}
			}
			assertThat(successes).isEqualTo(1);
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void cleanupDeletesOnlyTokensExpiredMoreThanSevenDaysAgo() {
		tokenService.issue(landlordId, TokenPurpose.PASSWORD_RESET, null);
		clock.advance(Duration.ofDays(7));
		String recent = tokenService.issue(landlordId, TokenPurpose.EMAIL_VERIFICATION, "kim@example.com").rawToken();

		clock.advance(Duration.ofMinutes(31));
		cleanupJob.deleteExpiredTokens();

		assertThat(jdbcTemplate.queryForList("select purpose from account_token", String.class))
				.containsExactly("EMAIL_VERIFICATION");
		assertThat(tokenService.findUsable(recent, TokenPurpose.EMAIL_VERIFICATION)).isPresent();
	}

	@Test
	void databaseRequiresTargetEmailOnlyForEmailPurposes() {
		assertThatThrownBy(() -> insertRaw("PASSWORD_RESET", "kim@example.com"))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("ck_account_token_target_email");
		assertThatThrownBy(() -> insertRaw("EMAIL_VERIFICATION", null))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("ck_account_token_target_email");
		assertThatThrownBy(() -> insertRaw("EMAIL_CHANGE", "New@Example.com"))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("ck_account_token_email_normalized");
	}

	@Test
	void entityRejectsTargetEmailMismatch() {
		assertThatThrownBy(() -> tokenService.issue(landlordId, TokenPurpose.EMAIL_CHANGE, null))
				.isInstanceOf(IllegalArgumentException.class);
	}

	private void insertRaw(String purpose, String targetEmail) {
		jdbcTemplate.update(
				"""
						insert into account_token (landlord_id, purpose, token_hash, target_email, expires_at, created_at, updated_at)
						values (?, ?, ?, ?, now() + interval '1 hour', now(), now())
						""",
				landlordId, purpose, SecureTokens.sha256Hex(SecureTokens.newToken()), targetEmail);
	}

}
