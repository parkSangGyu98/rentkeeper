package com.rentkeeper.support;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 통합 테스트 사이의 데이터 정리. 테스트 컨텍스트(와 DB 컨테이너)는 여러 테스트 클래스가 공유하므로, 로그인·가입을 여러 번 하는
 * 테스트는 레이트 리미트 카운터까지 지워야 서로 영향을 주지 않는다.
 */
public final class TestData {

	private TestData() {
	}

	public static void reset(JdbcTemplate jdbcTemplate) {
		jdbcTemplate.update("delete from spring_session");
		jdbcTemplate.update("delete from rate_limit_bucket");
		// account_token, login_failure는 landlord 삭제 시 함께 지워진다 (ON DELETE CASCADE)
		jdbcTemplate.update("delete from landlord");
		// landlord에 FK가 없는 대기 테이블 (V11)
		jdbcTemplate.update("delete from kakao_unlink_pending");
		unlockScheduledJobs(jdbcTemplate);
	}

	/**
	 * 배치 잠금(V15)을 모두 푼다. 앞 테스트가 작업을 부르면 1분(lockAtLeastFor) 동안 같은 작업이 건너뛰어진다. 행을 지우면
	 * 안 된다: ShedLock은 한 번 만든 잠금 행을 기억해 두고 UPDATE만 하므로, 행이 없으면 잠금을 못 잡은 것으로 보고 작업을
	 * 건너뛴다.
	 */
	public static void unlockScheduledJobs(JdbcTemplate jdbcTemplate) {
		jdbcTemplate.update("update shedlock set lock_until = locked_at");
	}

}
