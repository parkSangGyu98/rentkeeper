package com.rentkeeper.demo.application;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/** 매시 20분(서울)에 만료된 데모 계정을 지운다 (M6 결정 3). 서버가 여러 대여도 한 곳에서만 돈다(ADR-022) */
@Component
public class DemoAccountPurgeJob {

	static final String CRON = "0 20 * * * *";

	private final DemoAccountPurger purger;

	DemoAccountPurgeJob(DemoAccountPurger purger) {
		this.purger = purger;
	}

	@Scheduled(cron = CRON, zone = "Asia/Seoul")
	@SchedulerLock(name = "demo-account-purge", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
	public void run() {
		purger.run();
	}

}
