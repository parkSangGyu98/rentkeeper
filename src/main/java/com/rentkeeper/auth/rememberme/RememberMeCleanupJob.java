package com.rentkeeper.auth.rememberme;

import java.sql.Timestamp;
import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 30일 넘게 쓰이지 않은 remember-me 토큰을 매일 지운다 (ADR-013). 이미 만료라 로그인에 쓰일 수 없지만 DB에 남지
 * 않게 한다.
 */
@Component
public class RememberMeCleanupJob {

	private static final Logger log = LoggerFactory.getLogger(RememberMeCleanupJob.class);

	private final JdbcTemplate jdbcTemplate;

	private final Clock clock;

	RememberMeCleanupJob(JdbcTemplate jdbcTemplate, Clock clock) {
		this.jdbcTemplate = jdbcTemplate;
		this.clock = clock;
	}

	@Scheduled(cron = "0 10 4 * * *", zone = "Asia/Seoul")
	public void deleteExpiredTokens() {
		int deleted = jdbcTemplate.update("delete from persistent_logins where last_used < ?",
				Timestamp.from(clock.instant().minus(HashingRememberMeServices.VALIDITY)));
		log.info("만료 remember-me 토큰 정리: {}건 삭제", deleted);
	}

}
