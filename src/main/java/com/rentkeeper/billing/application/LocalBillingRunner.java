package com.rentkeeper.billing.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 개발자 브라우저 확인용: 앱이 뜰 때 청구 생성과 납부일 지남 전환을 한 번 돌린다. <b>local 프로필 + 명시한 설정</b>일 때만
 * 등록된다. "오늘"을 바꾸려면 app.dev.today를 함께 준다(ClockConfig).
 *
 * <pre>
 * .\gradlew.bat bootRun --args="--app.billing.run-on-startup=true"
 * .\gradlew.bat bootRun --args="--app.billing.run-on-startup=true --app.dev.today=2026-12-01"
 * </pre>
 *
 * HTTP 경로는 만들지 않는다(외부에서 호출할 방법이 없게). prod에서는 이 빈이 없다 (LocalBillingRunnerTests가
 * 검사). ShedLock을 거치지 않고 바로 부른다: 같은 실행을 반복해도 결과가 같은지 보는 용도다.
 */
@Component
@Profile("local")
@ConditionalOnProperty(name = "app.billing.run-on-startup", havingValue = "true")
class LocalBillingRunner implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(LocalBillingRunner.class);

	private final ChargeGenerator generator;

	private final ChargeOverdueMarker overdueMarker;

	LocalBillingRunner(ChargeGenerator generator, ChargeOverdueMarker overdueMarker) {
		this.generator = generator;
		this.overdueMarker = overdueMarker;
	}

	@Override
	public void run(ApplicationArguments args) {
		log.warn("[local] 청구 생성·납부일 지남 배치를 수동으로 한 번 실행합니다 (app.billing.run-on-startup=true)");
		log.warn("[local] 청구 생성 결과: {}", generator.run());
		log.warn("[local] 납부일 지남 결과: {}", overdueMarker.run());
	}

}
