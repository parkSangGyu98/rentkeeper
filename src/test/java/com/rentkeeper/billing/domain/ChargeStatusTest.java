package com.rentkeeper.billing.domain;

import static com.rentkeeper.billing.domain.ChargeStatus.CANCELED;
import static com.rentkeeper.billing.domain.ChargeStatus.ISSUED;
import static com.rentkeeper.billing.domain.ChargeStatus.OVERDUE;
import static com.rentkeeper.billing.domain.ChargeStatus.PAID;
import static com.rentkeeper.billing.domain.ChargeStatus.PARTIALLY_PAID;
import static com.rentkeeper.billing.domain.ChargeStatus.SCHEDULED;
import static com.rentkeeper.billing.domain.ChargeStatus.TENANT_REPORTED;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** 전이표 49칸 전부 (ADR-023, PRD 12장 필수 4) */
class ChargeStatusTest {

	/**
	 * 표의 열 순서: SCHEDULED, ISSUED, TENANT_REPORTED, PARTIALLY_PAID, PAID, OVERDUE,
	 * CANCELED
	 */
	private static final ChargeStatus[] ORDER = {SCHEDULED, ISSUED, TENANT_REPORTED, PARTIALLY_PAID, PAID, OVERDUE,
			CANCELED};

	// 1 = 허용, 0 = 금지. 대각선(같은 상태)은 0
	private static final int[][] TABLE = {
			// SCH ISS REP PAR PAI OVR CAN
			{0, 1, 0, 1, 1, 0, 1}, // SCHEDULED
			{0, 0, 1, 1, 1, 1, 1}, // ISSUED
			{0, 1, 0, 1, 1, 1, 1}, // TENANT_REPORTED
			{0, 1, 1, 0, 1, 1, 0}, // PARTIALLY_PAID: 취소는 입금을 먼저 지워야 한다
			{0, 1, 0, 1, 0, 1, 0}, // PAID: 취소 불가 (PRD)
			{0, 1, 1, 1, 1, 0, 1}, // OVERDUE
			{0, 0, 0, 0, 0, 0, 0}, // CANCELED: 끝
	};

	@Test
	void everyCellMatchesTheApprovedTable() {
		assertThat(ORDER).containsExactly(ChargeStatus.values());
		List<String> mismatches = new ArrayList<>();
		for (int from = 0; from < ORDER.length; from++) {
			for (int to = 0; to < ORDER.length; to++) {
				boolean expected = TABLE[from][to] == 1;
				if (ORDER[from].canTransitionTo(ORDER[to]) != expected) {
					mismatches.add(ORDER[from] + " -> " + ORDER[to] + " expected " + expected);
				}
			}
		}
		assertThat(mismatches).isEmpty();
	}

	@Test
	void overdueBatchSkipsTenantReported() {
		assertThat(ChargeStatus.overdueCandidates()).containsExactlyInAnyOrder(ISSUED, PARTIALLY_PAID)
				.doesNotContain(TENANT_REPORTED);
	}

}
