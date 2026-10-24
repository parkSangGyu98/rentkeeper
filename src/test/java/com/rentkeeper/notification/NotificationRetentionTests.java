package com.rentkeeper.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.rentkeeper.TestcontainersConfiguration;
import com.rentkeeper.notification.application.NotificationRetention;
import com.rentkeeper.support.MutableClock;
import com.rentkeeper.support.MutableClockConfiguration;
import com.rentkeeper.support.TestData;

/** 만든 지 365일이 지난 알림 행만 지운다. 운영 알림 행(임대인 없음)도 지운다 (M6 커밋 5) */
@SpringBootTest
@Import({TestcontainersConfiguration.class, MutableClockConfiguration.class})
class NotificationRetentionTests {

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	NotificationRetention retention;

	@Autowired
	MutableClock clock;

	@BeforeEach
	void setUp() {
		clock.set(MutableClockConfiguration.START);
		TestData.reset(jdbc);
	}

	@Test
	void deletesRowsOlderThanAYear() {
		Instant now = MutableClockConfiguration.START;
		ops("old", now.minus(Duration.ofDays(366)));
		ops("edge", now.minus(Duration.ofDays(365)).plusSeconds(60));
		ops("new", now.minus(Duration.ofDays(30)));

		assertThat(retention.deleteOld()).isEqualTo(1);

		assertThat(jdbc.queryForList("select dedupe_key from notification order by dedupe_key", String.class))
				.containsExactly("edge", "new");
		assertThat(retention.deleteOld()).as("다시 돌아도 같다").isZero();
	}

	private void ops(String key, Instant createdAt) {
		jdbc.update("insert into notification (type, channel, recipient_type, ops_code, scheduled_for, dedupe_key, "
				+ "status, skip_reason, next_attempt_at, created_at, updated_at) values ('OPS_ALERT', 'EMAIL', 'OPS', "
				+ "'X', current_date, ?, 'SKIPPED', 'NO_ADDRESS', now(), ?, ?)", key, Timestamp.from(createdAt),
				Timestamp.from(createdAt));
	}

}
