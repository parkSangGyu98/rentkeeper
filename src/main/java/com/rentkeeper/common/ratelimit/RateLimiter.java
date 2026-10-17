package com.rentkeeper.common.ratelimit;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * DB(rate_limit_bucket)에 저장하는 고정 창 카운터 (ADR-012). 서버를 늘려도 같은 한도를 공유한다.
 * <p>
 * 카운트 증가와 창 초기화를 INSERT ... ON CONFLICT 한 문장으로 처리하므로, 동시에 요청이 몰려도 정확히 센다.
 */
@Component
public class RateLimiter {

	// 창이 끝났으면 1부터 다시 세고, 아니면 1 더한다. 증가한 뒤의 값을 돌려준다
	private static final String HIT_SQL = """
			insert into rate_limit_bucket (bucket_key, window_started_at, hit_count, expires_at)
			values (?, ?, 1, ?)
			on conflict (bucket_key) do update set
			    hit_count = case when rate_limit_bucket.expires_at <= excluded.window_started_at
			                     then 1 else rate_limit_bucket.hit_count + 1 end,
			    window_started_at = case when rate_limit_bucket.expires_at <= excluded.window_started_at
			                             then excluded.window_started_at else rate_limit_bucket.window_started_at end,
			    expires_at = case when rate_limit_bucket.expires_at <= excluded.window_started_at
			                      then excluded.expires_at else rate_limit_bucket.expires_at end
			returning hit_count
			""";

	private final JdbcTemplate jdbcTemplate;

	private final Clock clock;

	public RateLimiter(JdbcTemplate jdbcTemplate, Clock clock) {
		this.jdbcTemplate = jdbcTemplate;
		this.clock = clock;
	}

	/**
	 * 요청 1회를 기록하고, 한도 안이면 true.
	 *
	 * @param subject
	 *            IP, 계정 id, 이메일 해시 등 (개인정보 원문 대신 해시를 넘길 것)
	 */
	public boolean tryAcquire(RateLimitRule rule, String subject) {
		Instant now = clock.instant();
		Integer hits = jdbcTemplate.queryForObject(HIT_SQL, Integer.class, rule.key(subject), Timestamp.from(now),
				Timestamp.from(now.plus(rule.window())));
		return hits != null && hits <= rule.limit();
	}

	/** 한도를 넘었으면 {@link RateLimitExceededException}(HTTP 429) */
	public void acquireOrThrow(RateLimitRule rule, String subject) {
		if (!tryAcquire(rule, subject)) {
			throw new RateLimitExceededException(rule);
		}
	}

	int deleteExpired() {
		return jdbcTemplate.update("delete from rate_limit_bucket where expires_at <= ?",
				Timestamp.from(clock.instant()));
	}

}
