package com.rentkeeper.lease.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

/**
 * 세입자 링크의 쓸 수 있는 기간 (M4 결정 4, ADR-026): 실제 끝(끝난 날, 없으면 나가는 날) + 90일까지. 끝 날짜가 둘
 * 다 없으면 쓸 수 없다. 닫은 링크는 쓸 수 없다. 마지막으로 연 시각은 하루에 한 번만 바꾼다.
 */
class TenantAccessTokenTest {

	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	private static final LocalDate END = LocalDate.of(2027, 1, 1);

	@Test
	void usableUntilIsActualEndPlusNinetyDays() {
		assertThat(TenantAccessToken.usableUntil(END, null)).as("종료하지 않음: 나가는 날 + 90일")
				.contains(LocalDate.of(2027, 4, 1));
		assertThat(TenantAccessToken.usableUntil(END, LocalDate.of(2026, 10, 17))).as("종료함: 끝난 날 + 90일")
				.contains(LocalDate.of(2027, 1, 15));
		assertThat(TenantAccessToken.usableUntil(null, LocalDate.of(2026, 10, 17))).contains(LocalDate.of(2027, 1, 15));
	}

	@Test
	void withoutAnyEndDateTheLinkIsNotUsable() {
		assertThat(TenantAccessToken.usableUntil(null, null)).isEmpty();
		TenantAccessToken token = TenantAccessToken.open(1, 2, "a".repeat(64), TenantLinkPurpose.LANDLORD);
		assertThat(token.usableOn(LocalDate.of(2026, 10, 17), null, null)).as("닫힌 쪽으로 실패").isFalse();
	}

	@Test
	void usableThroughTheLastDayOnly() {
		TenantAccessToken token = TenantAccessToken.open(1, 2, "a".repeat(64), TenantLinkPurpose.LANDLORD);
		assertThat(token.usableOn(LocalDate.of(2027, 4, 1), END, null)).isTrue();
		assertThat(token.usableOn(LocalDate.of(2027, 4, 2), END, null)).isFalse();
	}

	@Test
	void revokedLinkIsNotUsableAndCannotBeRevokedTwice() {
		TenantAccessToken token = TenantAccessToken.open(1, 2, "a".repeat(64), TenantLinkPurpose.LANDLORD);
		token.revoke(Instant.parse("2026-10-17T00:00:00Z"), TenantLinkRevokeReason.LANDLORD_CLOSED);

		assertThat(token.isOpen()).isFalse();
		assertThat(token.usableOn(LocalDate.of(2026, 10, 17), END, null)).isFalse();
		assertThatThrownBy(
				() -> token.revoke(Instant.parse("2026-10-18T00:00:00Z"), TenantLinkRevokeReason.LANDLORD_CLOSED))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void lastUsedIsRecordedOncePerSeoulDay() {
		TenantAccessToken token = TenantAccessToken.open(1, 2, "a".repeat(64), TenantLinkPurpose.LANDLORD);

		assertThat(token.markUsed(Instant.parse("2026-10-17T00:00:00Z"), SEOUL)).isTrue();
		assertThat(token.markUsed(Instant.parse("2026-10-17T14:59:59Z"), SEOUL)).as("서울 10/17 23:59").isFalse();
		assertThat(token.markUsed(Instant.parse("2026-10-17T15:00:00Z"), SEOUL)).as("서울 10/18 00:00").isTrue();
		assertThat(token.getLastUsedAt()).isEqualTo(Instant.parse("2026-10-17T15:00:00Z"));
	}

	@Test
	void toStringHasNoHash() {
		assertThat(TenantAccessToken.open(1, 2, "b".repeat(64), TenantLinkPurpose.LANDLORD).toString())
				.doesNotContain("bbbb");
	}

}
