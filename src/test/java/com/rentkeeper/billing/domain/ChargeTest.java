package com.rentkeeper.billing.domain;

import static com.rentkeeper.billing.domain.ChargeStatus.CANCELED;
import static com.rentkeeper.billing.domain.ChargeStatus.ISSUED;
import static com.rentkeeper.billing.domain.ChargeStatus.OVERDUE;
import static com.rentkeeper.billing.domain.ChargeStatus.PAID;
import static com.rentkeeper.billing.domain.ChargeStatus.PARTIALLY_PAID;
import static com.rentkeeper.billing.domain.ChargeStatus.SCHEDULED;
import static com.rentkeeper.billing.domain.ChargeStatus.TENANT_REPORTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;

import org.junit.jupiter.api.Test;

/** 상태 재계산 (ADR-023, M3 결정 7·10) */
class ChargeTest {

	private static final YearMonth MONTH = YearMonth.of(2026, 11);

	private static final LocalDate DUE = LocalDate.of(2026, 11, 5);

	private static final LocalDate BEFORE_DUE = DUE.minusDays(1);

	private static final LocalDate AFTER_DUE = DUE.plusDays(1);

	private static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");

	@Test
	void newChargeIsScheduledThenIssued() {
		Charge charge = charge(550_000);
		assertThat(charge.getStatus()).isEqualTo(SCHEDULED);
		assertThat(charge.getIssuedAt()).isNull();

		assertThat(charge.issue(NOW)).isEqualTo(new StatusChange(SCHEDULED, ISSUED));
		assertThat(charge.getIssuedAt()).isEqualTo(NOW);
		assertThatThrownBy(() -> charge.issue(NOW)).as("두 번 발행할 수 없다").isInstanceOf(ChargeStateException.class);
	}

	@Test
	void issuedPastDueBecomesOverdueOnlyAfterRefresh() {
		// 누락 복구: 납부일이 지난 달을 늦게 만들어도 먼저 ISSUED, 이어서 OVERDUE (SCHEDULED → OVERDUE는 없다)
		Charge charge = charge(550_000);
		charge.issue(NOW);
		assertThat(charge.refresh(AFTER_DUE, NOW)).contains(new StatusChange(ISSUED, OVERDUE));
	}

	@Test
	void dueDayItselfIsNotOverdue() {
		Charge charge = issued(550_000);
		assertThat(charge.refresh(DUE, NOW)).isEmpty();
		assertThat(charge.refresh(AFTER_DUE, NOW)).contains(new StatusChange(ISSUED, OVERDUE));
	}

	@Test
	void unissuedChargeNeverBecomesOverdue() {
		Charge charge = charge(550_000);
		assertThat(charge.refresh(AFTER_DUE.plusMonths(1), NOW)).isEmpty();
		assertThat(charge.getStatus()).isEqualTo(SCHEDULED);
	}

	@Test
	void partialThenFullPaymentBecomesPaidWithTimestamp() {
		Charge charge = issued(550_000);

		assertThat(charge.changePaid(200_000, BEFORE_DUE, NOW)).contains(new StatusChange(ISSUED, PARTIALLY_PAID));
		assertThat(charge.balance()).isEqualTo(350_000);
		assertThat(charge.getPaidAt()).isNull();

		Instant paidAt = NOW.plusSeconds(60);
		assertThat(charge.changePaid(550_000, BEFORE_DUE, paidAt)).contains(new StatusChange(PARTIALLY_PAID, PAID));
		assertThat(charge.getPaidAt()).isEqualTo(paidAt);
		assertThat(charge.balance()).isZero();
	}

	@Test
	void overpaymentIsAllowedAndShown() {
		Charge charge = issued(550_000);
		assertThat(charge.changePaid(600_000, BEFORE_DUE, NOW)).contains(new StatusChange(ISSUED, PAID));
		assertThat(charge.overpaid()).isEqualTo(50_000);
		assertThat(charge.balance()).isZero();
	}

	@Test
	void partialPaymentWhileOverdueStaysOverdue() {
		Charge charge = issued(550_000);
		charge.refresh(AFTER_DUE, NOW);

		assertThat(charge.changePaid(200_000, AFTER_DUE, NOW)).as("납부일 지남 중 일부 입금은 OVERDUE 유지").isEmpty();
		assertThat(charge.getStatus()).isEqualTo(OVERDUE);
		assertThat(charge.changePaid(550_000, AFTER_DUE, NOW)).contains(new StatusChange(OVERDUE, PAID));
	}

	@Test
	void partiallyPaidBecomesOverdueAfterDueDate() {
		Charge charge = issued(550_000);
		charge.changePaid(200_000, BEFORE_DUE, NOW);
		assertThat(charge.refresh(AFTER_DUE, NOW)).contains(new StatusChange(PARTIALLY_PAID, OVERDUE));
	}

	@Test
	void deletingPaymentFromPaidAfterDueDateBecomesOverdue() {
		// M3 결정 7: PAID에서 입금을 지워 일부 받음이 됐는데 납부일이 지났으면 OVERDUE
		Charge charge = issued(550_000);
		charge.changePaid(550_000, BEFORE_DUE, NOW);

		assertThat(charge.changePaid(200_000, AFTER_DUE, NOW)).contains(new StatusChange(PAID, OVERDUE));
		assertThat(charge.getPaidAt()).as("다 받은 시각은 지운다 (ck_charge_paid_at)").isNull();
	}

	@Test
	void deletingPaymentFromPaidBeforeDueDateGoesBack() {
		Charge partial = issued(550_000);
		partial.changePaid(550_000, BEFORE_DUE, NOW);
		assertThat(partial.changePaid(200_000, BEFORE_DUE, NOW)).contains(new StatusChange(PAID, PARTIALLY_PAID));

		Charge none = issued(550_000);
		none.changePaid(550_000, BEFORE_DUE, NOW);
		assertThat(none.changePaid(0, BEFORE_DUE, NOW)).contains(new StatusChange(PAID, ISSUED));
	}

	@Test
	void raisingTotalOfPaidChargeMakesItPartiallyPaid() {
		Charge charge = issued(550_000);
		charge.changePaid(550_000, BEFORE_DUE, NOW);
		assertThat(charge.changeTotal(600_000, BEFORE_DUE, NOW)).contains(new StatusChange(PAID, PARTIALLY_PAID));
		assertThat(charge.changeTotal(500_000, BEFORE_DUE, NOW)).contains(new StatusChange(PARTIALLY_PAID, PAID));
	}

	@Test
	void tenantReportIsExcludedFromOverdueAndRejectionAfterDueDateMakesOverdue() {
		Charge charge = issued(550_000);
		assertThat(charge.reportByTenant(BEFORE_DUE, NOW)).contains(new StatusChange(ISSUED, TENANT_REPORTED));

		assertThat(charge.refresh(AFTER_DUE, NOW)).as("신고 상태는 납부일 지남 배치가 건드리지 않는다").isEmpty();
		assertThat(charge.rejectTenantReport(AFTER_DUE, NOW)).contains(new StatusChange(TENANT_REPORTED, OVERDUE));
	}

	@Test
	void recordingPaymentResolvesTenantReport() {
		Charge charge = issued(550_000);
		charge.changePaid(200_000, BEFORE_DUE, NOW);
		assertThat(charge.reportByTenant(BEFORE_DUE, NOW)).as("나머지 신고")
				.contains(new StatusChange(PARTIALLY_PAID, TENANT_REPORTED));

		assertThat(charge.changePaid(400_000, BEFORE_DUE, NOW))
				.contains(new StatusChange(TENANT_REPORTED, PARTIALLY_PAID));
		assertThat(charge.getTenantReportedAt()).isNull();
	}

	@Test
	void tenantCannotReportUnissuedOrPaidCharge() {
		assertThatThrownBy(() -> charge(550_000).reportByTenant(BEFORE_DUE, NOW))
				.isInstanceOf(ChargeStateException.class);
		Charge paid = issued(550_000);
		paid.changePaid(550_000, BEFORE_DUE, NOW);
		assertThatThrownBy(() -> paid.reportByTenant(BEFORE_DUE, NOW)).isInstanceOf(ChargeStateException.class);
	}

	@Test
	void cancelOnlyWithoutPayments() {
		Charge overdue = issued(550_000);
		overdue.refresh(AFTER_DUE, NOW);
		assertThat(overdue.cancel("계약 종료로 자동 취소", NOW)).isEqualTo(new StatusChange(OVERDUE, CANCELED));
		assertThat(overdue.getCanceledAt()).isEqualTo(NOW);
		assertThat(overdue.getCancelReason()).isEqualTo("계약 종료로 자동 취소");

		Charge partial = issued(550_000);
		partial.changePaid(1, BEFORE_DUE, NOW);
		assertThatThrownBy(() -> partial.cancel("사유", NOW)).as("입금을 먼저 지워야 한다")
				.isInstanceOf(ChargeStateException.class);

		Charge paid = issued(550_000);
		paid.changePaid(550_000, BEFORE_DUE, NOW);
		assertThatThrownBy(() -> paid.cancel("사유", NOW)).as("PAID 취소 불가 (PRD)")
				.isInstanceOf(ChargeStateException.class);

		assertThatThrownBy(() -> issued(550_000).cancel(" ", NOW)).as("사유 필수")
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void canceledChargeIsFinal() {
		Charge charge = issued(550_000);
		charge.cancel("잘못 만듦", NOW);

		assertThat(charge.refresh(AFTER_DUE, NOW)).isEmpty();
		assertThatThrownBy(() -> charge.changePaid(100, BEFORE_DUE, NOW)).isInstanceOf(ChargeStateException.class);
		assertThatThrownBy(() -> charge.changeTotal(100, BEFORE_DUE, NOW)).isInstanceOf(ChargeStateException.class);
		assertThatThrownBy(() -> charge.cancel("다시", NOW)).isInstanceOf(ChargeStateException.class);
		assertThat(charge.getStatus()).isEqualTo(CANCELED);
	}

	@Test
	void dueDateMustBeInBillingMonthAndAmountsNonNegative() {
		assertThatThrownBy(() -> Charge.create(1, 1, MONTH, LocalDate.of(2026, 12, 1), 1, ChargeSource.AUTO))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> Charge.create(1, 1, MONTH, DUE, -1, ChargeSource.AUTO))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> issued(1).changePaid(-1, DUE, NOW)).isInstanceOf(IllegalArgumentException.class);
	}

	private static Charge charge(long total) {
		return Charge.create(1, 10, MONTH, DUE, total, ChargeSource.AUTO);
	}

	private static Charge issued(long total) {
		Charge charge = charge(total);
		charge.issue(NOW);
		return charge;
	}

}
