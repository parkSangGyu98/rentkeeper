package com.rentkeeper.lease.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.rentkeeper.lease.domain.UnitOccupancyRule.Occupancy;
import com.rentkeeper.property.domain.UnitStatus;

/**
 * 호실 상태와 "현재 계약" 규칙 (M2 결정 1, ADR-019). 기간은 [들어오는 날, 나가는 날).
 */
class UnitOccupancyRuleTest {

	private static final LocalDate TODAY = LocalDate.of(2026, 10, 17);

	@Test
	void noActiveLeaseIsVacant() {
		assertThat(UnitOccupancyRule.evaluate(List.of(), TODAY))
				.isEqualTo(new Occupancy(UnitStatus.VACANT, null, false));
		Lease ended = lease(1, "2026-01-01", "2027-01-01", LeaseStatus.ENDED);
		assertThat(UnitOccupancyRule.evaluate(List.of(ended), TODAY).status()).as("종료 처리한 계약은 보지 않는다")
				.isEqualTo(UnitStatus.VACANT);
	}

	@Test
	void coveringLeaseIsOccupied() {
		Lease lease = lease(1, "2026-01-01", "2027-01-01", LeaseStatus.ACTIVE);

		assertThat(UnitOccupancyRule.evaluate(List.of(lease), TODAY))
				.isEqualTo(new Occupancy(UnitStatus.OCCUPIED, 1L, false));
		assertThat(UnitOccupancyRule.evaluate(List.of(lease), LocalDate.of(2026, 1, 1)).status()).as("들어오는 날 포함")
				.isEqualTo(UnitStatus.OCCUPIED);
		assertThat(UnitOccupancyRule.evaluate(List.of(lease), LocalDate.of(2026, 12, 31)).status())
				.isEqualTo(UnitStatus.OCCUPIED);
	}

	@Test
	void moveOutDayItselfIsAfterThePeriod() {
		Lease lease = lease(1, "2026-01-01", "2026-10-17", LeaseStatus.ACTIVE);

		assertThat(UnitOccupancyRule.evaluate(List.of(lease), TODAY)).as("나가는 날 당일은 기간 밖 → 종료 처리 전이면 기간 지남")
				.isEqualTo(new Occupancy(UnitStatus.OVERDUE, 1L, true));
	}

	@Test
	void futureLeaseIsUpcomingAndEarliestIsCurrent() {
		Lease later = lease(2, "2027-03-01", "2028-03-01", LeaseStatus.ACTIVE);
		Lease sooner = lease(1, "2026-11-01", "2027-03-01", LeaseStatus.ACTIVE);

		assertThat(UnitOccupancyRule.evaluate(List.of(later, sooner), TODAY))
				.isEqualTo(new Occupancy(UnitStatus.UPCOMING, 1L, false));
	}

	@Test
	void overdueWithoutNewLeaseShowsLatestOverdueAsCurrent() {
		Lease older = lease(1, "2024-01-01", "2025-01-01", LeaseStatus.ACTIVE);
		Lease newer = lease(2, "2025-01-01", "2026-01-01", LeaseStatus.ACTIVE);
		Lease upcoming = lease(3, "2026-11-01", "2027-11-01", LeaseStatus.ACTIVE);

		assertThat(UnitOccupancyRule.evaluate(List.of(older, newer, upcoming), TODAY))
				.as("기간 지남이 입주 예정보다 먼저: 종료 처리부터 하도록").isEqualTo(new Occupancy(UnitStatus.OVERDUE, 2L, true));
	}

	@Test
	void newLeaseWinsOverForgottenOldLeaseButWarns() {
		// 옛 세입자가 10/1에 나갔는데 종료 처리를 잊었고, 같은 날 새 세입자가 들어왔다 ([) 경계라 둘 다 ACTIVE 가능)
		Lease forgotten = lease(1, "2025-10-01", "2026-10-01", LeaseStatus.ACTIVE);
		Lease current = lease(2, "2026-10-01", "2027-10-01", LeaseStatus.ACTIVE);

		assertThat(UnitOccupancyRule.evaluate(List.of(forgotten, current), TODAY))
				.isEqualTo(new Occupancy(UnitStatus.OCCUPIED, 2L, true));
	}

	static Lease lease(long id, String start, String end, LeaseStatus status) {
		LeaseTerms terms = new LeaseTerms(0, 1, LocalDate.parse(start), LocalDate.parse(end), "국민은행",
				new AccountNumber("1234567890"), new PersonName("김임대"), null);
		Lease lease = Lease.register(1L, 1L, 1L, terms);
		set(lease, "id", id);
		set(lease, "status", status);
		if (status == LeaseStatus.ENDED) {
			set(lease, "endedOn", LocalDate.parse(start));
		}
		return lease;
	}

	private static void set(Lease lease, String field, Object value) {
		try {
			Field f = Lease.class.getDeclaredField(field);
			f.setAccessible(true);
			f.set(lease, value);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

}
