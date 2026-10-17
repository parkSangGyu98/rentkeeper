package com.rentkeeper.billing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.rentkeeper.billing.application.ChargePlanner.PlannedCharge;
import com.rentkeeper.billing.domain.ChargeCalendar;
import com.rentkeeper.lease.application.LeaseBillingTerms;
import com.rentkeeper.lease.application.LeaseBillingTerms.Rent;

/** 자동 생성 달 계산 (ADR-024, PRD 12장 필수 2·3, M3 결정 2·4·20·21) */
class ChargePlannerTest {

	@Test
	void dueDayIsClampedToLastDayOfMonth() {
		assertThat(ChargeCalendar.dueDate(YearMonth.of(2027, 2), 31)).isEqualTo("2027-02-28");
		assertThat(ChargeCalendar.dueDate(YearMonth.of(2028, 2), 31)).as("윤년").isEqualTo("2028-02-29");
		assertThat(ChargeCalendar.dueDate(YearMonth.of(2028, 2), 29)).isEqualTo("2028-02-29");
		assertThat(ChargeCalendar.dueDate(YearMonth.of(2027, 2), 29)).isEqualTo("2027-02-28");
		assertThat(ChargeCalendar.dueDate(YearMonth.of(2026, 4), 31)).isEqualTo("2026-04-30");
		assertThat(ChargeCalendar.dueDate(YearMonth.of(2026, 5), 31)).isEqualTo("2026-05-31");
		assertThatThrownBy(() -> ChargeCalendar.dueDate(YearMonth.of(2026, 5), 0))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void issueDateMayFallInPreviousMonth() {
		assertThat(ChargeCalendar.issueDate(YearMonth.of(2026, 11), 5, 7)).isEqualTo("2026-10-29");
		assertThat(ChargeCalendar.issueDate(YearMonth.of(2026, 11), 5, 0)).isEqualTo("2026-11-05");
		assertThatThrownBy(() -> ChargeCalendar.issueDate(YearMonth.of(2026, 11), 5, 29))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void createsMonthOnceIssueDateArrives() {
		LeaseBillingTerms terms = terms(5, "2026-10-01", "2027-10-01", 7, "2026-09-20");

		assertThat(months(terms, "2026-10-28")).as("11월 청구는 10/29부터").containsExactly(YearMonth.of(2026, 10));
		assertThat(months(terms, "2026-10-29")).containsExactly(YearMonth.of(2026, 10), YearMonth.of(2026, 11));
	}

	@Test
	void leapAndShortMonthsGetClampedDueDates() {
		LeaseBillingTerms terms = terms(31, "2027-01-01", "2028-06-01", 0, "2026-12-01");

		List<PlannedCharge> planned = new java.util.ArrayList<>(
				ChargePlanner.plan(terms, LocalDate.parse("2027-03-31"), Set.of()));
		planned.addAll(ChargePlanner.plan(terms, LocalDate.parse("2028-04-30"), Set.of()));
		assertThat(planned).filteredOn(p -> p.month().equals(YearMonth.of(2027, 2))).singleElement()
				.extracting(PlannedCharge::dueDate).isEqualTo(LocalDate.parse("2027-02-28"));
		assertThat(planned).filteredOn(p -> p.month().equals(YearMonth.of(2028, 2))).singleElement()
				.extracting(PlannedCharge::dueDate).isEqualTo(LocalDate.parse("2028-02-29"));
		assertThat(planned).filteredOn(p -> p.month().equals(YearMonth.of(2027, 4))).singleElement()
				.extracting(PlannedCharge::dueDate).isEqualTo(LocalDate.parse("2027-04-30"));
	}

	@Test
	void monthsBeforeRegistrationDueDateAreNotAutomatic() {
		// 8/1 입주, 10/18 등록: 8·9월과 이미 납부일(10/5)이 지난 10월은 자동으로 만들지 않는다 (M3 결정 2)
		LeaseBillingTerms dueDay5 = terms(5, "2026-08-01", "2027-08-01", 7, "2026-10-18");
		assertThat(months(dueDay5, "2026-10-29")).containsExactly(YearMonth.of(2026, 11));

		// 납부일이 등록일 당일이면 자동으로 만든다
		LeaseBillingTerms dueDay18 = terms(18, "2026-08-01", "2027-08-01", 7, "2026-10-18");
		assertThat(months(dueDay18, "2026-10-18")).containsExactly(YearMonth.of(2026, 10));
	}

	@Test
	void lastMonthIsMonthOfDayBeforeActualEnd() {
		// 나가는 날 1일: 그 전날의 달까지 (M3 결정 4)
		assertThat(months(terms(5, "2026-01-01", "2026-04-01", 0, "2025-12-01"), "2026-12-31"))
				.containsExactly(YearMonth.of(2026, 1), YearMonth.of(2026, 2), YearMonth.of(2026, 3));
		// 나가는 날 말일: 그 달까지
		assertThat(months(terms(5, "2026-01-01", "2026-03-31", 0, "2025-12-01"), "2026-12-31"))
				.containsExactly(YearMonth.of(2026, 1), YearMonth.of(2026, 2), YearMonth.of(2026, 3));
		// 나가는 날 2일: 그 달(1일 하루)도 청구
		assertThat(months(terms(5, "2026-01-01", "2026-03-02", 0, "2025-12-01"), "2026-12-31"))
				.containsExactly(YearMonth.of(2026, 1), YearMonth.of(2026, 2), YearMonth.of(2026, 3));
	}

	@Test
	void endedLeaseUsesEndedOnAndSameDayEndHasNoMonth() {
		// 조기 종료: 실제 끝(ended_on) 기준
		LeaseBillingTerms ended = terms(5, "2026-01-01", "2026-02-15", 0, "2025-12-01");
		assertThat(months(ended, "2026-12-31")).containsExactly(YearMonth.of(2026, 1), YearMonth.of(2026, 2));
		assertThat(months(terms(5, "2026-01-01", "2026-01-01", 0, "2025-12-01"), "2026-12-31")).as("하루도 살지 않음")
				.isEmpty();
	}

	@Test
	void recoveryIsCappedToTwelveMonths() {
		LeaseBillingTerms terms = terms(5, "2024-01-01", "2028-01-01", 0, "2023-12-01");

		List<YearMonth> months = months(terms, "2026-10-18");
		assertThat(months).first().isEqualTo(YearMonth.of(2025, 10));
		assertThat(months).last().isEqualTo(YearMonth.of(2026, 10));
		assertThat(months).hasSize(13);
	}

	@Test
	void existingMonthsIncludingCanceledAreSkipped() {
		LeaseBillingTerms terms = terms(5, "2026-08-01", "2027-08-01", 0, "2026-07-01");

		List<PlannedCharge> planned = ChargePlanner.plan(terms, LocalDate.parse("2026-10-18"),
				Set.of(YearMonth.of(2026, 9)));
		assertThat(planned).extracting(PlannedCharge::month).containsExactly(YearMonth.of(2026, 8),
				YearMonth.of(2026, 10));
	}

	@Test
	void rentChangeAppliesFromItsMonthAndZeroMonthsAreSkipped() {
		LeaseBillingTerms terms = new LeaseBillingTerms(1, 1, 5, LocalDate.parse("2026-08-01"),
				LocalDate.parse("2027-08-01"), 0, LocalDate.parse("2026-07-01"),
				List.of(new Rent(YearMonth.of(2026, 10), 0, 0), new Rent(YearMonth.of(2026, 8), 500_000, 50_000),
						new Rent(YearMonth.of(2026, 11), 550_000, 0)));

		List<PlannedCharge> planned = ChargePlanner.plan(terms, LocalDate.parse("2026-11-30"), Set.of());
		assertThat(planned).extracting(PlannedCharge::month, PlannedCharge::total).containsExactly(
				org.assertj.core.groups.Tuple.tuple(YearMonth.of(2026, 8), 550_000L),
				org.assertj.core.groups.Tuple.tuple(YearMonth.of(2026, 9), 550_000L),
				org.assertj.core.groups.Tuple.tuple(YearMonth.of(2026, 11), 550_000L));
	}

	private static List<YearMonth> months(LeaseBillingTerms terms, String today) {
		return ChargePlanner.plan(terms, LocalDate.parse(today), Set.of()).stream().map(PlannedCharge::month).toList();
	}

	private static LeaseBillingTerms terms(int dueDay, String start, String actualEnd, int issueDaysBefore,
			String registeredOn) {
		LocalDate startDate = LocalDate.parse(start);
		return new LeaseBillingTerms(1, 1, dueDay, startDate, LocalDate.parse(actualEnd), issueDaysBefore,
				LocalDate.parse(registeredOn), List.of(new Rent(YearMonth.from(startDate), 500_000, 50_000)));
	}

}
