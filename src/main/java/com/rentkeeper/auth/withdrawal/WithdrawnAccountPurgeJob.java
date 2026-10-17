package com.rentkeeper.auth.withdrawal;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/**
 * 매일 03:30(서울) 탈퇴 계정 파기와 카카오 연결 끊기 재시도 (ADR-015). 서버가 여러 대여도 한 곳에서만 돈다
 * (ADR-022). 겹쳐 돌아도 계정마다 행 잠금(for update)으로 다시 확인해 결과는 같다.
 */
@Component
public class WithdrawnAccountPurgeJob {

	private final WithdrawnAccountPurger purger;

	WithdrawnAccountPurgeJob(WithdrawnAccountPurger purger) {
		this.purger = purger;
	}

	@Scheduled(cron = "0 30 3 * * *", zone = "Asia/Seoul")
	// 카카오 호출이 가장 길다(1건 최대 8초 = 연결 3초 + 응답 5초). 2시간이면 대기 900건까지 감당한다 (ADR-022)
	@SchedulerLock(name = "withdrawn-account-purge", lockAtMostFor = "PT2H", lockAtLeastFor = "PT1M")
	public void run() {
		purger.run();
	}

}
