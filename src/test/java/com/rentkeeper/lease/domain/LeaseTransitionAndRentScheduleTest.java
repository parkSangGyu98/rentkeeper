package com.rentkeeper.lease.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.rentkeeper.lease.domain.Lease.LeaseStateException;
import com.rentkeeper.lease.domain.RentSchedule.RentChangeException;
import com.rentkeeper.lease.domain.RentSchedule.Violation;

/**
 * 계약 상태 전이(ACTIVE → ENDED 한 방향, 종료 후 수정 불가)와 월세 이력 규칙 (M2 결정 3·4). 이번 달 = 2026년
 * 10월.
 */
class LeaseTransitionAndRentScheduleTest {

	private static final LocalDate TODAY = LocalDate.of(2026, 10, 17);

	private static final YearMonth THIS_MONTH = YearMonth.of(2026, 10);

	// ---------------------------------------------------------------- 종료

	@Test
	void activeLeaseEndsOnceBetweenStartAndToday() {
		Lease lease = lease("2026-01-01", "2027-01-01");

		lease.end(TODAY, TODAY);

		assertThat(lease.getStatus()).isEqualTo(LeaseStatus.ENDED);
		assertThat(lease.getEndedOn()).isEqualTo(TODAY);
		assertThatThrownBy(() -> lease.end(TODAY, TODAY)).as("ENDED → 다시 종료 불가")
				.isInstanceOf(LeaseStateException.class);
		assertThatThrownBy(() -> lease.changeTerms(terms(lease, "2028-01-01"))).as("종료 후 수정 불가")
				.isInstanceOf(LeaseStateException.class);
	}

	@Test
	void endedOnMustBeFromStartToToday() {
		Lease lease = lease("2026-10-10", "2027-10-10");

		assertThatThrownBy(() -> lease.end(TODAY.plusDays(1), TODAY)).as("미래 퇴거는 나가는 날 수정으로")
				.isInstanceOf(InvalidLeaseValueException.class).hasMessageContaining("endedOn");
		assertThatThrownBy(() -> lease.end(LocalDate.of(2026, 10, 9), TODAY)).as("들어오는 날 전")
				.isInstanceOf(InvalidLeaseValueException.class);
		lease.end(LocalDate.of(2026, 10, 10), TODAY);
		assertThat(lease.getEndedOn()).as("들어오는 날 당일 종료 허용").isEqualTo(LocalDate.of(2026, 10, 10));
	}

	@Test
	void changeTermsKeepsStartDate() {
		Lease lease = lease("2026-01-01", "2027-01-01");

		lease.changeTerms(terms(lease, "2027-06-30"));

		assertThat(lease.getEndDate()).isEqualTo(LocalDate.of(2027, 6, 30));
		assertThat(lease.getStartDate()).isEqualTo(LocalDate.of(2026, 1, 1));
		assertThat(lease.lastMonth()).as("나가는 날은 기간 밖 → 그 전날의 달").isEqualTo(YearMonth.of(2027, 6));
		assertThat(lease("2026-01-01", "2027-07-01").lastMonth()).isEqualTo(YearMonth.of(2027, 6));
	}

	// ---------------------------------------------------------------- 월세 이력

	@Test
	void newChangeMustBeFromThisMonthAfterFirstMonthWithinLease() {
		Lease lease = lease("2026-01-01", "2027-07-01");
		RentSchedule schedule = schedule(lease, row(lease, 1, "2026-01"));

		assertThat(schedule.earliestChangeMonth()).contains(THIS_MONTH);
		assertThat(schedule.latestChangeMonth()).isEqualTo(YearMonth.of(2027, 6));
		assertViolation(() -> schedule.change(YearMonth.of(2026, 9), 1, 0, null), Violation.MONTH_IN_PAST);
		assertViolation(() -> schedule.change(YearMonth.of(2027, 7), 1, 0, null), Violation.AFTER_LEASE);
		assertThat(schedule.change(THIS_MONTH, 600000, 0, "인상")).as("이번 달에 행이 없으면 새로 만든다").isEmpty();
	}

	@Test
	void firstMonthIsCorrectedOnlyThroughLeaseEdit() {
		Lease lease = lease("2026-12-01", "2027-12-01");
		RentSchedule schedule = schedule(lease, row(lease, 1, "2026-12"));

		assertThat(schedule.earliestChangeMonth()).as("아직 안 들어온 계약: 첫 달 다음 달부터").contains(YearMonth.of(2027, 1));
		assertViolation(() -> schedule.change(YearMonth.of(2026, 12), 1, 0, null), Violation.FIRST_MONTH);
		assertViolation(() -> schedule.cancellable(1L), Violation.FIRST_MONTH);
		schedule.correctFirst(700000, 10000);
		assertThat(schedule.first().getMonthlyRent()).isEqualTo(700000);
	}

	@Test
	void futureChangeCanBeReplacedOrCancelledButStartedChangeCannot() {
		Lease lease = lease("2026-01-01", "2027-07-01");
		LeaseRentHistory current = row(lease, 2, "2026-10");
		LeaseRentHistory future = row(lease, 3, "2027-01");
		RentSchedule schedule = schedule(lease, row(lease, 1, "2026-01"), current, future);

		assertThat(schedule.change(YearMonth.of(2027, 1), 650000, 5000, "교체")).as("같은 미래 달은 교체").contains(future);
		assertThat(future.getMonthlyRent()).isEqualTo(650000);
		assertViolation(() -> schedule.change(THIS_MONTH, 1, 0, null), Violation.ALREADY_STARTED);
		assertViolation(() -> schedule.cancellable(2L), Violation.ALREADY_STARTED);
		assertThat(schedule.cancellable(3L)).isSameAs(future);
		assertViolation(() -> schedule.cancellable(99L), Violation.NOT_FOUND);
		assertThat(schedule.hasChangeAfter(YearMonth.of(2026, 12))).as("나가는 날을 12월로 당기면 1월 변경이 남는다").isTrue();
		assertThat(schedule.hasChangeAfter(YearMonth.of(2027, 1))).isFalse();
	}

	@Test
	void endedLeaseRejectsRentChanges() {
		Lease lease = lease("2026-01-01", "2027-07-01");
		LeaseRentHistory future = row(lease, 2, "2027-01");
		RentSchedule schedule = schedule(lease, row(lease, 1, "2026-01"), future);
		lease.end(TODAY, TODAY);

		assertViolation(() -> schedule.change(YearMonth.of(2027, 2), 1, 0, null), Violation.LEASE_ENDED);
		assertViolation(() -> schedule.cancellable(2L), Violation.LEASE_ENDED);
		assertViolation(() -> schedule.correctFirst(1, 0), Violation.LEASE_ENDED);
	}

	// ---------------------------------------------------------------- 도우미

	private static void assertViolation(Runnable action, Violation expected) {
		assertThatThrownBy(action::run).isInstanceOf(RentChangeException.class)
				.satisfies(e -> assertThat(((RentChangeException) e).violation()).isEqualTo(expected));
	}

	private static RentSchedule schedule(Lease lease, LeaseRentHistory... rows) {
		return new RentSchedule(lease, new ArrayList<>(List.of(rows)), THIS_MONTH);
	}

	private static Lease lease(String start, String end) {
		LeaseTerms terms = new LeaseTerms(0, 5, LocalDate.parse(start), LocalDate.parse(end), "국민은행",
				new AccountNumber("1234567890"), new PersonName("김임대"), null);
		Lease lease = Lease.register(1L, 1L, 1L, terms);
		set(Lease.class, lease, "id", 1L);
		return lease;
	}

	private static LeaseTerms terms(Lease lease, String end) {
		return new LeaseTerms(0, 5, lease.getStartDate(), LocalDate.parse(end), "국민은행", new AccountNumber("1234567890"),
				new PersonName("김임대"), null);
	}

	private static LeaseRentHistory row(Lease lease, long id, String month) {
		LeaseRentHistory row = LeaseRentHistory.create(lease, YearMonth.parse(month), 500000, 50000, null);
		set(LeaseRentHistory.class, row, "id", id);
		return row;
	}

	private static void set(Class<?> type, Object target, String field, Object value) {
		try {
			Field f = type.getDeclaredField(field);
			f.setAccessible(true);
			f.set(target, value);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

}
