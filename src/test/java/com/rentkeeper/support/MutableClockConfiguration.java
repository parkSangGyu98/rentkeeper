package com.rentkeeper.support;

import java.time.Instant;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 앱의 Clock 대신 테스트가 조작할 수 있는 Clock을 쓴다. 테스트마다 {@link MutableClock#set}으로 시작 시각을
 * 맞춘다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class MutableClockConfiguration {

	public static final Instant START = Instant.parse("2026-10-17T00:00:00Z");

	@Bean
	@Primary
	MutableClock mutableClock() {
		return new MutableClock(START);
	}

}
