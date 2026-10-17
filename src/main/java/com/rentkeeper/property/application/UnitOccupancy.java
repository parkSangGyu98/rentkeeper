package com.rentkeeper.property.application;

import java.util.Collection;
import java.util.Map;

import com.rentkeeper.property.domain.UnitStatus;

/**
 * 호실 상태를 계약에서 계산해 알려 주는 쪽 (PROP-3). 계약은 lease 모듈에 있고 property는 lease에 기댈 수
 * 없으므로(ADR-020), property가 이 인터페이스를 정하고 lease가 구현한다. 구현이 없으면(계약 모듈이 생기기 전) 모든
 * 호실을 공실로 본다.
 */
public interface UnitOccupancy {

	/**
	 * @return 호실 ID별 상태. 계약이 없는 호실은 빠져도 된다(공실로 본다)
	 */
	Map<Long, UnitStatus> statusOf(long landlordId, Collection<Long> unitIds);

}
