package com.rentkeeper.lease.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

/** 세입자 수신 거부 전이 (TEN-6, M5 결정 9): 받음 ⇄ 거부, 같은 쪽으로 다시 바꾸면 아무것도 바뀌지 않는다 */
class TenantOptOutTest {

	private static final Instant FIRST = Instant.parse("2026-10-17T00:00:00Z");

	private static final Instant LATER = Instant.parse("2026-10-18T00:00:00Z");

	@Test
	void receivesUntilOptingOutAndKeepsTheFirstTime() {
		Tenant tenant = tenant();
		assertThat(tenant.notificationsOptedOut()).isFalse();

		assertThat(tenant.optOutOfNotifications(FIRST)).isTrue();
		assertThat(tenant.optOutOfNotifications(LATER)).as("이미 거부함").isFalse();

		assertThat(tenant.notificationsOptedOut()).isTrue();
		assertThat(tenant.getNotifyOptOutAt()).isEqualTo(FIRST);
	}

	@Test
	void optingBackInClearsAndIsIdempotent() {
		Tenant tenant = tenant();
		assertThat(tenant.optInToNotifications()).as("이미 받는 중").isFalse();
		tenant.optOutOfNotifications(FIRST);

		assertThat(tenant.optInToNotifications()).isTrue();
		assertThat(tenant.optInToNotifications()).isFalse();
		assertThat(tenant.notificationsOptedOut()).isFalse();
		assertThat(tenant.getNotifyOptOutAt()).isNull();
	}

	@Test
	void landlordEditingTheTenantKeepsTheChoice() {
		Tenant tenant = tenant();
		tenant.optOutOfNotifications(FIRST);

		tenant.change(new PersonName("김길동"), PhoneNumber.parse("01033334444"), "b".repeat(64), null);

		assertThat(tenant.notificationsOptedOut()).isTrue();
	}

	private static Tenant tenant() {
		return Tenant.register(1, new PersonName("홍길동"), PhoneNumber.parse("01011112222"), "a".repeat(64), null);
	}

}
