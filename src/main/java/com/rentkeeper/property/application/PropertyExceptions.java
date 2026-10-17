package com.rentkeeper.property.application;

import com.rentkeeper.common.error.BusinessException;
import com.rentkeeper.common.error.ErrorCode;

/**
 * 건물·호실의 업무 규칙 위반. 화면은 코드에 맞춰 친절한 한글 문구를 고른다. DB 예외 메시지는 화면에 내보내지 않는다.
 */
public final class PropertyExceptions {

	private PropertyExceptions() {
	}

	/** 같은 임대인 안에 같은 이름의 건물이 있다 (uk_property_landlord_name) */
	public static class DuplicatePropertyNameException extends BusinessException {

		DuplicatePropertyNameException(Throwable cause) {
			super(ErrorCode.DUPLICATE_PROPERTY_NAME, "property name already exists", cause);
		}

	}

	/** 같은 건물 안에 같은 이름의 호실이 있다 (uk_unit_property_name) */
	public static class DuplicateUnitNameException extends BusinessException {

		DuplicateUnitNameException(Throwable cause) {
			super(ErrorCode.DUPLICATE_UNIT_NAME, "unit name already exists in property", cause);
		}

	}

	/** 호실이 있는 건물은 지울 수 없다 (M2 결정 11) */
	public static class PropertyHasUnitsException extends BusinessException {

		PropertyHasUnitsException() {
			super(ErrorCode.PROPERTY_HAS_UNITS, "property has units");
		}

	}

	/** 계약 기록이 있는 호실은 지울 수 없다 (M2 결정 11, fk_lease_unit) */
	public static class UnitHasLeasesException extends BusinessException {

		UnitHasLeasesException(Throwable cause) {
			super(ErrorCode.UNIT_HAS_LEASES, "unit has leases", cause);
		}

	}

}
