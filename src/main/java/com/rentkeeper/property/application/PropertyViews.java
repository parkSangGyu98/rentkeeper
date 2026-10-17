package com.rentkeeper.property.application;

import java.util.List;

import com.rentkeeper.property.domain.UnitStatus;
import com.rentkeeper.property.domain.UnitType;

/**
 * 화면·다른 모듈에 넘기는 건물·호실 정보 (엔티티를 밖으로 내보내지 않는다, PRD 10장).
 */
public final class PropertyViews {

	private PropertyViews() {
	}

	/** 건물 목록 카드 */
	public record PropertySummary(long id, String name, String address, int unitCount, int vacantCount,
			int occupiedCount) {
	}

	public record PropertyDetail(long id, String name, String address, String memo, List<UnitRow> units) {

		public boolean hasUnits() {
			return !units.isEmpty();
		}

	}

	public record UnitRow(long id, String name, UnitType type, String memo, UnitStatus status) {
	}

	/** 호실 하나 (계약 화면 등 다른 모듈에서도 쓴다) */
	public record UnitView(long id, long propertyId, String propertyName, String name, UnitType type, String memo) {

		/** 화면 표시용 "행복빌라 101호" */
		public String displayName() {
			return propertyName + " " + name;
		}

	}

	/** 건물 등록·수정 입력 */
	public record PropertyInput(String name, String address, String memo) {
	}

	/** 호실 등록·수정 입력 */
	public record UnitInput(String name, UnitType type, String memo) {
	}

}
