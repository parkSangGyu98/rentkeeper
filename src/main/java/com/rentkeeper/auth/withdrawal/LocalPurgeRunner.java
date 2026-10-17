package com.rentkeeper.auth.withdrawal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 개발자 브라우저 확인용: 앱이 뜰 때 파기 배치를 한 번 돌린다. <b>local 프로필 + 명시한 설정</b>일 때만 등록된다.
 *
 * <pre>
 * .\gradlew.bat bootRun --args="--app.purge.run-on-startup=true"
 * </pre>
 *
 * HTTP 경로는 만들지 않는다(외부에서 호출할 방법이 없게). prod에서는 이 빈이 없다 (LocalPurgeRunnerTests가
 * 검사).
 */
@Component
@Profile("local")
@ConditionalOnProperty(name = "app.purge.run-on-startup", havingValue = "true")
class LocalPurgeRunner implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(LocalPurgeRunner.class);

	private final WithdrawnAccountPurger purger;

	LocalPurgeRunner(WithdrawnAccountPurger purger) {
		this.purger = purger;
	}

	@Override
	public void run(ApplicationArguments args) {
		log.warn("[local] 파기 배치를 수동으로 한 번 실행합니다 (app.purge.run-on-startup=true)");
		purger.run();
	}

}
