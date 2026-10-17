package com.rentkeeper.auth.lock;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 계정 기준 로그인 잠금 (ADR-012): 비밀번호를 연속 5회 틀리면 15분 동안 비밀번호 로그인을 막는다.
 * <ul>
 * <li>잠긴 동안의 실패는 횟수에 더하지 않는다 → 공격자가 잠금을 계속 연장할 수 없다.</li>
 * <li>잠금이 끝나면 다시 5번의 기회가 생긴다. 로그인에 성공하거나 비밀번호를 재설정하면 기록을 지운다.</li>
 * <li>이미 로그인된 세션·remember-me 자동 로그인은 막지 않는다 (남이 일부러 잠가도 피해가 "새 기기에서 15분 대기"로
 * 그친다).</li>
 * </ul>
 */
@Service
public class LoginLockService {

	public static final int MAX_FAILURES = 5;

	public static final Duration LOCK_DURATION = Duration.ofMinutes(15);

	private final JdbcTemplate jdbcTemplate;

	private final Clock clock;

	public LoginLockService(JdbcTemplate jdbcTemplate, Clock clock) {
		this.jdbcTemplate = jdbcTemplate;
		this.clock = clock;
	}

	public boolean isLocked(long landlordId) {
		Boolean locked = jdbcTemplate.query(
				"select locked_until > ? from login_failure where landlord_id = ? and locked_until is not null",
				rs -> rs.next() ? rs.getBoolean(1) : Boolean.FALSE, Timestamp.from(clock.instant()), landlordId);
		return Boolean.TRUE.equals(locked);
	}

	/**
	 * 비밀번호 실패 1회를 기록한다. 같은 계정에 실패가 동시에 몰려도 행 잠금(FOR UPDATE)으로 정확히 센다.
	 *
	 * @return 이번 실패로 잠겼으면 true
	 */
	@Transactional
	public boolean recordFailure(long landlordId) {
		Instant now = clock.instant();
		Timestamp nowTs = Timestamp.from(now);
		jdbcTemplate.update(
				"""
						insert into login_failure (landlord_id, failed_count, locked_until, last_failed_at, created_at, updated_at)
						values (?, 0, null, ?, ?, ?) on conflict (landlord_id) do nothing
						""",
				landlordId, nowTs, nowTs, nowTs);
		Failure current = jdbcTemplate.queryForObject(
				"select failed_count, locked_until from login_failure where landlord_id = ? for update",
				(rs, row) -> new Failure(rs.getInt(1), toInstant(rs.getTimestamp(2))), landlordId);

		if (current.lockedUntil() != null && current.lockedUntil().isAfter(now)) {
			return false; // 잠긴 동안에는 세지 않는다
		}
		// 잠금이 끝났으면 처음부터 다시 센다
		int failures = (current.lockedUntil() != null ? 0 : current.count()) + 1;
		Instant lockedUntil = failures >= MAX_FAILURES ? now.plus(LOCK_DURATION) : null;
		jdbcTemplate.update("""
				update login_failure set failed_count = ?, locked_until = ?, last_failed_at = ?, updated_at = ?
				where landlord_id = ?
				""", failures, lockedUntil == null ? null : Timestamp.from(lockedUntil), nowTs, nowTs, landlordId);
		return lockedUntil != null;
	}

	/** 로그인 성공·비밀번호 재설정 시 기록을 지운다 (잠금도 풀린다) */
	@Transactional
	public void clear(long landlordId) {
		jdbcTemplate.update("delete from login_failure where landlord_id = ?", landlordId);
	}

	private static Instant toInstant(Timestamp timestamp) {
		return timestamp == null ? null : timestamp.toInstant();
	}

	private record Failure(int count, Instant lockedUntil) {
	}

}
