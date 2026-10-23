package com.rentkeeper.billing.application;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/**
 * 매일 03:00(서울) 청구 자동 생성 (ADR-024). 서버가 여러 대여도 한 곳에서만 돈다(ADR-022). 겹쳐 돌아도 계약마다 행
 * 잠금과 UNIQUE로 결과는 같다.
 */
@Component
public class ChargeGenerationJob {

	static final String CRON = "0 0 3 * * *";

	private final ChargeGenerator generator;

	ChargeGenerationJob(ChargeGenerator generator) {
		this.generator = generator;
	}

	@Scheduled(cron = CRON, zone = "Asia/Seoul")
	// 계약 2만 건 측정값은 ADR-024. 잠금은 그보다 넉넉히 길게 둔다
	@SchedulerLock(name = "charge-generation", lockAtMostFor = "PT1H", lockAtLeastFor = "PT1M")
	public void run() {
		generator.run();
	}

}
