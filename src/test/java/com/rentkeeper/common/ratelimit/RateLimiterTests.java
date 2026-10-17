package com.rentkeeper.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.rentkeeper.TestcontainersConfiguration;
import com.rentkeeper.support.MutableClock;
import com.rentkeeper.support.MutableClockConfiguration;

/**
 * DB 고정 창 레이트 리미트 (ADR-012).
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, MutableClockConfiguration.class})
class RateLimiterTests {

	@Autowired
	RateLimiter rateLimiter;

	@Autowired
	MutableClock clock;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@BeforeEach
	void setUp() {
		clock.set(MutableClockConfiguration.START);
		jdbcTemplate.update("delete from rate_limit_bucket");
	}

	@Test
	void allowsUpToLimitThenBlocks() {
		for (int i = 0; i < RateLimitRule.SIGNUP_PER_IP.limit(); i++) {
			assertThat(rateLimiter.tryAcquire(RateLimitRule.SIGNUP_PER_IP, "10.0.0.1")).isTrue();
		}
		assertThat(rateLimiter.tryAcquire(RateLimitRule.SIGNUP_PER_IP, "10.0.0.1")).isFalse();
	}

	@Test
	void newWindowStartsAfterWindowEnds() {
		exhaust(RateLimitRule.SIGNUP_PER_IP, "10.0.0.1");

		clock.advance(Duration.ofMinutes(59));
		assertThat(rateLimiter.tryAcquire(RateLimitRule.SIGNUP_PER_IP, "10.0.0.1")).isFalse();
		clock.advance(Duration.ofMinutes(1));
		assertThat(rateLimiter.tryAcquire(RateLimitRule.SIGNUP_PER_IP, "10.0.0.1")).isTrue();
	}

	@Test
	void keysAndRulesAreIndependent() {
		exhaust(RateLimitRule.SIGNUP_PER_IP, "10.0.0.1");

		assertThat(rateLimiter.tryAcquire(RateLimitRule.SIGNUP_PER_IP, "10.0.0.2")).isTrue();
		assertThat(rateLimiter.tryAcquire(RateLimitRule.LOGIN_PER_IP, "10.0.0.1")).isTrue();
	}

	@Test
	void concurrentRequestsAreCountedExactly() throws Exception {
		int threads = 25;
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(threads);
		try {
			List<Future<Boolean>> results = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				results.add(executor.submit(() -> {
					start.await();
					return rateLimiter.tryAcquire(RateLimitRule.SIGNUP_PER_IP, "10.0.0.9");
				}));
			}
			start.countDown();
			int allowed = 0;
			for (Future<Boolean> result : results) {
				if (result.get()) {
					allowed++;
				}
			}
			assertThat(allowed).isEqualTo(RateLimitRule.SIGNUP_PER_IP.limit());
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void cleanupDeletesOnlyFinishedWindows() {
		rateLimiter.tryAcquire(RateLimitRule.LOGIN_PER_IP, "10.0.0.1"); // 10분 창
		rateLimiter.tryAcquire(RateLimitRule.SIGNUP_PER_IP, "10.0.0.1"); // 1시간 창

		clock.advance(Duration.ofMinutes(10));
		assertThat(rateLimiter.deleteExpired()).isEqualTo(1);
		assertThat(jdbcTemplate.queryForList("select bucket_key from rate_limit_bucket", String.class))
				.containsExactly("signup:ip:10.0.0.1");
	}

	private void exhaust(RateLimitRule rule, String subject) {
		for (int i = 0; i < rule.limit(); i++) {
			rateLimiter.tryAcquire(rule, subject);
		}
	}

}
