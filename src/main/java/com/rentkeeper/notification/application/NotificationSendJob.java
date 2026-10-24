package com.rentkeeper.notification.application;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/**
 * 30초마다 알림 보내기 (M5 결정 2). 서버가 여러 대여도 한 곳에서만 돈다(ADR-022). 겹쳐 돌아도 SKIP LOCKED로 같은
 * 행을 두 번 가져가지 않는다. 주기는 app.notification.worker-cron (테스트는 "-"로 끈다).
 */
@Component
public class NotificationSendJob {

	private final NotificationWorker worker;

	NotificationSendJob(NotificationWorker worker) {
		this.worker = worker;
	}

	@Scheduled(cron = "${app.notification.worker-cron}", zone = "Asia/Seoul")
	@SchedulerLock(name = "notification-send", lockAtMostFor = "PT10M", lockAtLeastFor = "PT5S")
	public void run() {
		worker.runOnce();
	}

}
