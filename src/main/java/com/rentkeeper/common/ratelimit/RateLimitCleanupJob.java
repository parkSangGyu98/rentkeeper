package com.rentkeeper.common.ratelimit;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

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
	public void deleteExpiredBuckets() {
		rateLimiter.deleteExpired();
	}

}
