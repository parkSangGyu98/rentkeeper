package com.rentkeeper.auth.token;

import java.time.Clock;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/**
 * 만료된 계정 토큰을 매일 정리한다 (ADR-011). 만료 후 7일은 남겨 두어 문의가 오면 "링크가 만료됐는지" 확인할 수 있게 한다.
 */
@Component
public class AccountTokenCleanupJob {

	static final Duration RETENTION_AFTER_EXPIRY = Duration.ofDays(7);

	private static final Logger log = LoggerFactory.getLogger(AccountTokenCleanupJob.class);

	private final AccountTokenRepository repository;

	private final Clock clock;

	AccountTokenCleanupJob(AccountTokenRepository repository, Clock clock) {
		this.repository = repository;
		this.clock = clock;
	}

	@Scheduled(cron = "0 0 4 * * *", zone = "Asia/Seoul")
	// 삭제 쿼리 한 번(1초 미만). 잠금이 트랜잭션 바깥이라 커밋 뒤에 풀린다 (ADR-022)
	@SchedulerLock(name = "account-token-cleanup", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
	@Transactional
	public void deleteExpiredTokens() {
		int deleted = repository.deleteExpiredBefore(clock.instant().minus(RETENTION_AFTER_EXPIRY));
		log.info("만료 계정 토큰 정리: {}건 삭제", deleted);
	}

}
