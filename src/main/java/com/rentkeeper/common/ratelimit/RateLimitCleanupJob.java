package com.rentkeeper.common.ratelimit;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/**
 * 창이 끝난 카운터를 10분마다 지운다. 카운터 키에 IP가 들어 있어 오래 남기지 않는다 (ADR-012).
 */
@Component
public class RateLimitCleanupJob {

	private final RateLimiter rateLimiter;

	RateLimitCleanupJob(RateLimiter rateLimiter) {
		this.rateLimiter = rateLimiter;
	}

	@Scheduled(fixedDelayString = "PT10M", initialDelayString = "PT10M")
	// 삭제 쿼리 한 번(1초 미만). 실행 간격(10분)보다 짧게 둬 서버가 죽어도 다음 차례에는 풀려 있다 (ADR-022)
	@SchedulerLock(name = "rate-limit-cleanup", lockAtMostFor = "PT9M", lockAtLeastFor = "PT1M")
	public void deleteExpiredBuckets() {
		rateLimiter.deleteExpired();
	}

}
