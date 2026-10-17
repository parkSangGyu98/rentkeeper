package com.rentkeeper.auth.withdrawal;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.rentkeeper.auth.kakao.KakaoUnlinkResult;

/**
 * 아직 끊지 못한 카카오 연결 끊기 작업 목록 (kakao_unlink_pending, V11, ADR-015). "끊기가 남아 있다" =
 * 이 테이블에 행이 있다.
 * <p>
 * 회원번호 한 개에 한 행이다(PK). 같은 회원번호가 다시 들어오면(탈퇴-재가입-탈퇴) 합치고, 탈퇴 시각은 더 이른 값을 유지한다.
 */
@Component
class KakaoUnlinkPendingStore {

	/** 재시도와 알림 판정에 쓰는 한 행. 개인정보인 회원번호가 들어 있으므로 로그에 찍지 않는다 */
	record Pending(String kakaoId, Instant withdrawnAt, int failureCount, KakaoUnlinkResult lastFailureType) {

		@Override
		public String toString() {
			return "Pending[failureCount=" + failureCount + ", lastFailureType=" + lastFailureType + "]";
		}

	}

	/** 오늘 남길 알림 요약 (건수만) */
	record AlertCounts(int config, int transientRepeated, int rejected, int severe, int pending) {

		boolean needsAlert() {
			return config > 0 || transientRepeated > 0 || rejected > 0 || severe > 0;
		}

	}

	private final JdbcTemplate jdbcTemplate;

	KakaoUnlinkPendingStore(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	/** 행이 있도록 보장한다 (파기 때). 이미 있으면 실패 기록은 그대로 두고 탈퇴 시각만 더 이른 값으로 */
	void ensure(String kakaoId, Instant withdrawnAt) {
		jdbcTemplate.update("""
				insert into kakao_unlink_pending (kakao_id, withdrawn_at) values (?, ?)
				on conflict (kakao_id) do update
				set withdrawn_at = least(kakao_unlink_pending.withdrawn_at, excluded.withdrawn_at)
				""", kakaoId, Timestamp.from(withdrawnAt));
	}

	/** 실패를 기록한다. 행이 없으면 만든다 (탈퇴 직후 실패) */
	void recordFailure(String kakaoId, Instant withdrawnAt, KakaoUnlinkResult type, Instant now) {
		if (!type.isFailure()) {
			throw new IllegalArgumentException("not a failure: " + type);
		}
		jdbcTemplate.update(
				"""
						insert into kakao_unlink_pending (kakao_id, withdrawn_at, failure_count, last_failed_at, last_failure_type)
						values (?, ?, 1, ?, ?)
						on conflict (kakao_id) do update
						set withdrawn_at = least(kakao_unlink_pending.withdrawn_at, excluded.withdrawn_at),
						    failure_count = kakao_unlink_pending.failure_count + 1,
						    last_failed_at = excluded.last_failed_at,
						    last_failure_type = excluded.last_failure_type
						""",
				kakaoId, Timestamp.from(withdrawnAt), Timestamp.from(now), type.name());
	}

	void delete(String kakaoId) {
		jdbcTemplate.update("delete from kakao_unlink_pending where kakao_id = ?", kakaoId);
	}

	List<Pending> findAll() {
		return jdbcTemplate.query("""
				select kakao_id, withdrawn_at, failure_count, last_failure_type
				from kakao_unlink_pending order by withdrawn_at
				""",
				(rs, rowNum) -> new Pending(rs.getString("kakao_id"), rs.getTimestamp("withdrawn_at").toInstant(),
						rs.getInt("failure_count"),
						rs.getString("last_failure_type") == null
								? null
								: KakaoUnlinkResult.valueOf(rs.getString("last_failure_type"))));
	}

	/**
	 * 알림 판정 (M1-c 결정 2). 행은 해결될 때까지 남으므로 매일 다시 센다.
	 *
	 * @param severeBefore
	 *            이 시각 이전에 탈퇴한 행은 "심각" (탈퇴 후 60일)
	 */
	AlertCounts countAlerts(Instant severeBefore, int transientThreshold) {
		return jdbcTemplate.queryForObject(
				"""
						select count(*) filter (where last_failure_type = 'CONFIG') as config,
						       count(*) filter (where last_failure_type = 'TRANSIENT' and failure_count >= ?) as transient_repeated,
						       count(*) filter (where last_failure_type = 'REJECTED') as rejected,
						       count(*) filter (where withdrawn_at <= ?) as severe,
						       count(*) as pending
						from kakao_unlink_pending
						""",
				(rs, rowNum) -> new AlertCounts(rs.getInt("config"), rs.getInt("transient_repeated"),
						rs.getInt("rejected"), rs.getInt("severe"), rs.getInt("pending")),
				transientThreshold, Timestamp.from(severeBefore));
	}

}
