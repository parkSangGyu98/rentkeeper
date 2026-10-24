package com.rentkeeper.demo.application;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 방문자용 임시 데모 (M6 결정 3).
 *
 * @param enabled
 *            첫 화면에 [데모로 둘러보기]를 보이고 데모 계정을 만들지 (환경변수 APP_DEMO_ENABLED, 기본 false)
 * @param maxAccounts
 *            동시에 있을 수 있는 데모 계정 수 (APP_DEMO_MAX_ACCOUNTS, 기본 50). 꽉 차면 새로 만들지
 *            않는다
 * @param lifetime
 *            데모 계정을 지우기까지의 시간 (APP_DEMO_LIFETIME, 기본 24시간)
 */
@ConfigurationProperties("app.demo")
public record DemoProperties(boolean enabled, Integer maxAccounts, Duration lifetime) {

	public DemoProperties {
		maxAccounts = maxAccounts == null ? 50 : maxAccounts;
		lifetime = lifetime == null ? Duration.ofHours(24) : lifetime;
		if (maxAccounts < 1 || lifetime.isNegative() || lifetime.isZero()) {
			throw new IllegalStateException("app.demo.max-accounts는 1 이상, app.demo.lifetime은 0보다 커야 합니다");
		}
	}

}
