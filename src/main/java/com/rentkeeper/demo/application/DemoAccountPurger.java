package com.rentkeeper.demo.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.rentkeeper.landlord.application.LandlordService;

/**
 * 만료된 데모 계정 지우기 (M6 결정 3). 탈퇴 계정 파기와 같은 방식이다: 세션·로그인 유지(FK 없음)를 먼저 지우고 계정 행을
 * 지우면 나머지는 CASCADE로 함께 지워진다. 데모가 아닌 계정은 SQL 조건으로 한 번 더 막는다.
 */
@Component
public class DemoAccountPurger {

	private static final Logger log = LoggerFactory.getLogger(DemoAccountPurger.class);

	private final LandlordService landlordService;

	private final JdbcTemplate jdbc;

	private final Clock clock;

	private final TransactionTemplate transaction;

	DemoAccountPurger(LandlordService landlordService, JdbcTemplate jdbc, Clock clock,
			PlatformTransactionManager transactionManager) {
		this.landlordService = landlordService;
		this.jdbc = jdbc;
		this.clock = clock;
		this.transaction = new TransactionTemplate(transactionManager);
	}

	/** @return 지운 데모 계정 수 */
	public int run() {
		Instant now = clock.instant();
		List<Long> ids = landlordService.expiredDemoIds(now);
		int purged = 0;
		for (long id : ids) {
			Integer deleted = transaction.execute(status -> {
				String principalName = String.valueOf(id);
				jdbc.update("delete from spring_session where principal_name = ?", principalName);
				jdbc.update("delete from persistent_logins where username = ?", principalName);
				return jdbc.update(
						"delete from landlord where id = ? and demo_expires_at is not null and demo_expires_at <= ?",
						id, Timestamp.from(now));
			});
			purged += deleted == null ? 0 : deleted;
		}
		if (purged > 0) {
			log.info("demo accounts purged: {}", purged);
		}
		return purged;
	}

}
