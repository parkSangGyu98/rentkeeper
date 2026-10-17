package com.rentkeeper.billing.application;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/**
 * 매일 00:10(서울) 납부일 지남 전환 (ADR-023). 납부일 다음 날 아침에는 이미 "납부일 지남"으로 보인다. 서버가 여러 대여도
 * 한 곳에서만 돈다(ADR-022).
 */
@Component
public class ChargeOverdueJob {

	static final String CRON = "0 10 0 * * *";

	private final ChargeOverdueMarker marker;

	ChargeOverdueJob(ChargeOverdueMarker marker) {
		this.marker = marker;
	}

	@Scheduled(cron = CRON, zone = "Asia/Seoul")
	// 처리량 측정값은 ADR-024. 잠금은 그보다 넉넉히 길게 둔다
	@SchedulerLock(name = "charge-overdue", lockAtMostFor = "PT1H", lockAtLeastFor = "PT1M")
	public void run() {
		marker.run();
	}

}
