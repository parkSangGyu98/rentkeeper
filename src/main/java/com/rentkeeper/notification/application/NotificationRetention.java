package com.rentkeeper.notification.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/**
 * 알림 행 보관 정리 (M5에서 넘김, M6 커밋 5). 만든 지 {@value #KEEP_DAYS}일이 지난 알림 행을 매일
 * 04:30(서울)에 지운다. 운영 알림 행(임대인 없음)은 임대인 파기로 지워지지 않으므로 여기서 지운다. 실제 보관 기간은 실서비스 전환
 * 때 전문가 검토로 정한다.
 * <p>
 * 행이 적어 임대인을 가로지르는 날짜 조건만으로 지운다(인덱스 없이 순차 스캔). 이력 화면은 최근 90일만 보이므로 지워도 화면은 바뀌지
 * 않는다.
 */
@Component
public class NotificationRetention {

	static final int KEEP_DAYS = 365;

	static final String CRON = "0 30 4 * * *";

	private static final Logger log = LoggerFactory.getLogger(NotificationRetention.class);

	private final JdbcTemplate jdbc;

	private final Clock clock;

	NotificationRetention(JdbcTemplate jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	@Scheduled(cron = CRON, zone = "Asia/Seoul")
	@SchedulerLock(name = "notification-retention", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
	public void run() {
		deleteOld();
	}

	/** @return 지운 행 수 */
	public int deleteOld() {
		Timestamp cutoff = Timestamp.from(clock.instant().minus(Duration.ofDays(KEEP_DAYS)));
		int deleted = jdbc.update("delete from notification where created_at < ?", cutoff);
		if (deleted > 0) {
			log.info("old notifications deleted: {}", deleted);
		}
		return deleted;
	}

}
