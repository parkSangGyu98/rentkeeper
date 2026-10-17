package com.rentkeeper.config;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * 현재 시각의 유일한 출처. 코드는 {@code LocalDate.now()} 대신 이 Clock을 주입받아
 * {@code LocalDate.now(clock)}을 쓴다. 테스트에서 {@code Clock.fixed(...)}로 바꿔 말일·자정
 * 경계를 재현하기 위해서다 (ADR-002).
 * <p>
 * <b>local 전용 "오늘 바꾸기"</b> (M3 결정 19): {@code app.dev.today=2026-12-01}을 주면 날짜만
 * 그 날로 옮기고 시각은 계속 흐른다. 청구 생성·납부일 지남을 브라우저로 확인할 때 쓴다. local 프로필이 아니거나 prod 프로필이
 * 켜져 있는데 값이 있으면 앱을 띄우지 않는다: 운영에서 날짜가 바뀌면 청구가 잘못 만들어진다. 배치 잠금(ShedLock)은 DB 시각을
 * 써서 영향받지 않는다.
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

	public static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	static final String DEV_TODAY = "app.dev.today";

	private static final Logger log = LoggerFactory.getLogger(ClockConfig.class);

	@Bean
	Clock clock(Environment environment) {
		// JVM 기본 시간대에 기대지 않고 zone을 명시한다
		Clock system = Clock.system(SEOUL);
		String devToday = environment.getProperty(DEV_TODAY, "").strip();
		if (devToday.isEmpty()) {
			return system;
		}
		if (!environment.acceptsProfiles(Profiles.of("local")) || environment.acceptsProfiles(Profiles.of("prod"))) {
			throw new IllegalStateException(DEV_TODAY + "는 local 프로필에서만 쓸 수 있습니다. 운영에서는 설정하지 마세요 (M3 결정 19).");
		}
		LocalDate target = LocalDate.parse(devToday);
		Duration shift = Duration.between(LocalDate.now(system).atStartOfDay(SEOUL), target.atStartOfDay(SEOUL));
		log.warn("[local] 오늘을 {}로 바꿔 실행합니다 ({}={}). 실제 날짜와 다릅니다", target, DEV_TODAY, devToday);
		return Clock.offset(system, shift);
	}

}
