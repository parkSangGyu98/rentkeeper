package com.rentkeeper.config;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 현재 시각의 유일한 출처. 코드는 {@code LocalDate.now()} 대신 이 Clock을 주입받아
 * {@code LocalDate.now(clock)}을 쓴다. 테스트에서 {@code Clock.fixed(...)}로 바꿔 말일·자정
 * 경계를 재현하기 위해서다 (ADR-002).
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

	public static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	@Bean
	Clock clock() {
		// JVM 기본 시간대에 기대지 않고 zone을 명시한다
		return Clock.system(SEOUL);
	}

}
