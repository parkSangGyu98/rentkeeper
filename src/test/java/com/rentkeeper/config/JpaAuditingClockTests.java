package com.rentkeeper.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.convention.TestBean;

import com.rentkeeper.TestcontainersConfiguration;
import com.rentkeeper.landlord.application.LandlordService;
import com.rentkeeper.landlord.domain.EmailAddress;

/**
 * created_at / updated_at이 JVM 시계가 아니라 주입된 Clock에서 온다는 것을 고정 시각으로 확인한다
 * (ADR-002).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class JpaAuditingClockTests {

	private static final Instant FIXED = Instant.parse("2026-02-28T15:00:00Z"); // KST 2026-03-01 00:00

	@TestBean
	Clock clock;

	static Clock clock() {
		return Clock.fixed(FIXED, ClockConfig.SEOUL);
	}

	@Autowired
	LandlordService landlordService;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Test
	void timestampsComeFromInjectedClock() {
		jdbcTemplate.update("delete from landlord");
		landlordService.registerLocal(EmailAddress.of("clock@example.com"), "{bcrypt}hash", "시계");

		OffsetDateTime createdAt = jdbcTemplate.queryForObject("select created_at from landlord", OffsetDateTime.class);
		OffsetDateTime updatedAt = jdbcTemplate.queryForObject("select updated_at from landlord", OffsetDateTime.class);
		assertThat(createdAt.toInstant()).isEqualTo(FIXED);
		assertThat(updatedAt.toInstant()).isEqualTo(FIXED);
	}

}
