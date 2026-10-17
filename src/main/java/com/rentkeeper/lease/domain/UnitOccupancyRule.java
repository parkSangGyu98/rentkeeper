package com.rentkeeper.lease.domain;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import com.rentkeeper.property.domain.UnitStatus;

/**
 * 호실 상태와 "현재 계약"을 정하는 규칙 (PROP-3, M2 결정 1, ADR-019). 호실의 ACTIVE 계약만 본다(종료 처리한
 * 계약은 상태에 영향이 없다). 기간은 [들어오는 날, 나가는 날)이다.
 * <ol>
 * <li>오늘이 기간 안인 계약이 있으면 → <b>입주중</b>, 그 계약이 현재 계약 (겹침 제약 때문에 많아야 하나)</li>
 * <li>없고, 나가는 날이 지난 계약이 있으면 → <b>기간 지남</b>, 그중 나가는 날이 가장 늦은 계약이 현재 계약</li>
 * <li>없고, 들어오는 날이 아직 안 온 계약이 있으면 → <b>입주 예정</b>, 그중 가장 빨리 들어오는 계약이 현재 계약</li>
 * <li>모두 없으면 → <b>공실</b>, 현재 계약 없음</li>
 * </ol>
 * 입주중이어도 나가는 날이 지났는데 종료 처리하지 않은 옛 계약이 함께 있으면 {@code staleActive}로 알려 "종료 처리를 잊은
 * 계약"을 화면에 안내한다. 예: 3/31에 나간 옛 계약을 종료하지 않은 채 3/31부터 새 계약이 시작된 경우.
 */
public final class UnitOccupancyRule {

	private UnitOccupancyRule() {
	}

	/**
	 * @param currentLeaseId
	 *            현재 계약 ID. 공실이면 null
	 * @param staleActive
	 *            나가는 날이 지났는데 종료 처리하지 않은 ACTIVE 계약이 있는지
	 */
	public record Occupancy(UnitStatus status, Long currentLeaseId, boolean staleActive) {
	}

	public static Occupancy evaluate(List<Lease> leasesOfUnit, LocalDate today) {
		List<Lease> active = leasesOfUnit.stream().filter(l -> l.getStatus() == LeaseStatus.ACTIVE).toList();
		List<Lease> overdue = active.stream().filter(l -> !l.getEndDate().isAfter(today)).toList();
		boolean stale = !overdue.isEmpty();

		Optional<Lease> covering = active.stream().filter(l -> l.coversDay(today)).findFirst();
		if (covering.isPresent()) {
			return new Occupancy(UnitStatus.OCCUPIED, covering.get().getId(), stale);
		}
		Optional<Lease> latestOverdue = overdue.stream().max(Comparator.comparing(Lease::getEndDate));
		if (latestOverdue.isPresent()) {
			return new Occupancy(UnitStatus.OVERDUE, latestOverdue.get().getId(), true);
		}
		Optional<Lease> nextUpcoming = active.stream().filter(l -> l.getStartDate().isAfter(today))
				.min(Comparator.comparing(Lease::getStartDate));
		return nextUpcoming.map(l -> new Occupancy(UnitStatus.UPCOMING, l.getId(), false))
				.orElseGet(() -> new Occupancy(UnitStatus.VACANT, null, false));
	}

}
