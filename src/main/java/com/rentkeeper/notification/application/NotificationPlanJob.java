package com.rentkeeper.notification.application;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/**
 * 매일 08:00(서울) 날짜로 정해지는 알림 계획 (M5 결정 4). 납부일 지남 전환(00:10)이 끝난 뒤라 D+1 대상이 이미 납부일
 * 지남 상태다. 만든 알림은 발송 시간(09:00)부터 나간다. 서버가 여러 대여도 한 곳에서만 돈다(ADR-022).
 */
@Component
public class NotificationPlanJob {

	static final String CRON = "0 0 8 * * *";

	private final NotificationPlanner planner;

	NotificationPlanJob(NotificationPlanner planner) {
		this.planner = planner;
	}

	@Scheduled(cron = CRON, zone = "Asia/Seoul")
	@SchedulerLock(name = "notification-plan", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
	public void run() {
		planner.planTenantNotices();
		planner.planLandlordDigests();
	}

}
