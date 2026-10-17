package com.rentkeeper.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.rentkeeper.TestcontainersConfiguration;
import com.rentkeeper.auth.rememberme.RememberMeCleanupJob;
import com.rentkeeper.auth.token.AccountTokenCleanupJob;
import com.rentkeeper.auth.withdrawal.WithdrawnAccountPurgeJob;
import com.rentkeeper.common.ratelimit.RateLimitCleanupJob;
import com.rentkeeper.support.MutableClock;
import com.rentkeeper.support.MutableClockConfiguration;
import com.rentkeeper.support.TestData;

/**
 * 배치 잠금(ShedLock, ADR-022)이 실제로 걸리는지 확인한다. 작업 메서드를 직접 부르면 스케줄러가 부른 것과 같은 프록시를
 * 지난다.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, MutableClockConfiguration.class})
class SchedulerLockTests {

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	MutableClock clock;

	@Autowired
	RememberMeCleanupJob rememberMeCleanupJob;

	@Autowired
	AccountTokenCleanupJob accountTokenCleanupJob;

	@Autowired
	WithdrawnAccountPurgeJob withdrawnAccountPurgeJob;

	@Autowired
	RateLimitCleanupJob rateLimitCleanupJob;

	@BeforeEach
	void setUp() {
		clock.set(MutableClockConfiguration.START);
		TestData.unlockScheduledJobs(jdbcTemplate);
		jdbcTemplate.update("delete from rate_limit_bucket");
	}

	@Test
	void everyJobTakesItsOwnLock() {
		rememberMeCleanupJob.deleteExpiredTokens();
		accountTokenCleanupJob.deleteExpiredTokens();
		withdrawnAccountPurgeJob.run();
		rateLimitCleanupJob.deleteExpiredBuckets();

		// setUp에서 모두 풀었으므로(lock_until = locked_at), 지금 잡혀 있는 잠금은 이 테스트가 잡은 것이다
		assertThat(jdbcTemplate.queryForList(
				"select name from shedlock where lock_until > now() at time zone 'utc' and locked_by <> ''",
				String.class)).containsExactlyInAnyOrder("account-token-cleanup", "rate-limit-cleanup",
						"remember-me-cleanup", "withdrawn-account-purge");
	}

	@Test
	void jobFinishedWithinLockAtLeastForDoesNotRunAgain() {
		rateLimitCleanupJob.deleteExpiredBuckets();
		insertExpiredBucket();

		// 1분(lockAtLeastFor) 안에 다시 시작하면 건너뛴다: 다른 서버가 같은 차례에 늦게 시작한 경우
		rateLimitCleanupJob.deleteExpiredBuckets();

		assertThat(jdbcTemplate.queryForObject("select count(*) from rate_limit_bucket", Integer.class))
				.as("두 번째 실행은 잠금 때문에 건너뛰어 지우지 않았다").isEqualTo(1);

		// 잠금이 풀리면 다시 돈다
		TestData.unlockScheduledJobs(jdbcTemplate);
		rateLimitCleanupJob.deleteExpiredBuckets();
		assertThat(jdbcTemplate.queryForObject("select count(*) from rate_limit_bucket", Integer.class)).isZero();
	}

	@Test
	void lockTimesUseDatabaseUtcClockNotApplicationClock() {
		// 앱 Clock을 1년 뒤 서울 자정 직전으로 옮겨도 잠금 시각은 DB 시각(UTC) 기준이다
		clock.set(
				MutableClockConfiguration.START.plus(Duration.ofDays(365)).plus(Duration.ofHours(14).plusMinutes(59)));

		rememberMeCleanupJob.deleteExpiredTokens();

		Map<String, Object> row = jdbcTemplate.queryForMap("""
				select extract(epoch from (locked_at - (now() at time zone 'utc'))) as locked_offset,
				       extract(epoch from (lock_until - locked_at)) as held
				from shedlock where name = 'remember-me-cleanup'
				""");
		assertThat(((Number) row.get("locked_offset")).doubleValue()).as("DB의 UTC 현재 시각과 몇 초 이내").isBetween(-10.0, 1.0);
		assertThat(((Number) row.get("held")).doubleValue()).as("끝난 뒤 lockAtLeastFor(1분)만큼 남긴다").isBetween(55.0, 61.0);
	}

	private void insertExpiredBucket() {
		jdbcTemplate.update("""
				insert into rate_limit_bucket (bucket_key, window_started_at, hit_count, expires_at)
				values ('test:lock', ?::timestamptz - interval '2 hours', 1, ?::timestamptz - interval '1 hour')
				""", clock.instant().toString(), clock.instant().toString());
	}

}
