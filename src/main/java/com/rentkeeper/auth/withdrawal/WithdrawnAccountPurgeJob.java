package com.rentkeeper.auth.withdrawal;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 매일 03:30(서울) 탈퇴 계정 파기와 카카오 연결 끊기 재시도 (ADR-015). 다시 돌아도 결과가 같아 ShedLock 없이
 * 시작한다(M3에서 도입).
 */
@Component
class WithdrawnAccountPurgeJob {

	private final WithdrawnAccountPurger purger;

	WithdrawnAccountPurgeJob(WithdrawnAccountPurger purger) {
		this.purger = purger;
	}

	@Scheduled(cron = "0 30 3 * * *", zone = "Asia/Seoul")
	void run() {
		purger.run();
	}

}
