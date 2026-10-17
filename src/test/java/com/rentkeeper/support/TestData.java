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
	}

}
