package com.rentkeeper.lease.application;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.rentkeeper.lease.domain.Lease;
import com.rentkeeper.lease.domain.LeaseRepository;
import com.rentkeeper.lease.domain.LeaseStatus;
import com.rentkeeper.lease.domain.UnitOccupancyRule;
import com.rentkeeper.property.application.UnitOccupancy;
import com.rentkeeper.property.domain.UnitStatus;

/**
 * 건물 목록·상세의 호실 상태를 계약에서 계산한다 (property가 정한 UnitOccupancy를 lease가 구현,
 * ADR-018·019). 규칙은 UnitOccupancyRule 하나뿐이라 호실 화면과 건물 화면의 상태가 항상 같다.
 */
@Component
class LeaseUnitOccupancy implements UnitOccupancy {

	private final LeaseRepository leases;

	private final Clock clock;

	LeaseUnitOccupancy(LeaseRepository leases, Clock clock) {
		this.leases = leases;
		this.clock = clock;
	}

	@Override
	@Transactional(readOnly = true)
	public Map<Long, UnitStatus> statusOf(long landlordId, Collection<Long> unitIds) {
		LocalDate today = LocalDate.now(clock);
		Map<Long, List<Lease>> byUnit = leases
				.findByLandlordIdAndStatusAndUnitIdIn(landlordId, LeaseStatus.ACTIVE, unitIds).stream()
				.collect(Collectors.groupingBy(Lease::getUnitId));
		return byUnit.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey,
				entry -> UnitOccupancyRule.evaluate(entry.getValue(), today).status()));
	}

}
