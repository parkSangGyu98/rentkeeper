package com.rentkeeper.property.web;

import com.rentkeeper.common.validation.MaxChars;
import com.rentkeeper.property.application.PropertyViews.PropertyInput;
import com.rentkeeper.property.application.PropertyViews.UnitInput;
import com.rentkeeper.property.domain.UnitType;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** 건물·호실 입력 폼. 길이 제한은 V13 컬럼과 같다. 오류 문구에 입력값을 넣지 않는다 */
public final class PropertyForms {

	private PropertyForms() {
	}

	public record PropertyForm(
			@NotBlank(message = "건물 이름을 입력해 주세요") @MaxChars(value = 50, message = "건물 이름은 50자 이하로 입력해 주세요") String name,
			@MaxChars(value = 200, message = "주소는 200자 이하로 입력해 주세요") String address,
			@MaxChars(value = 500, message = "메모는 500자 이하로 입력해 주세요") String memo) {

		static PropertyForm empty() {
			return new PropertyForm("", "", "");
		}

		static PropertyForm of(PropertyInput input) {
			return new PropertyForm(input.name(), nullToEmpty(input.address()), nullToEmpty(input.memo()));
		}

		PropertyInput toInput() {
			return new PropertyInput(name, address, memo);
		}

	}

	public record UnitForm(
			@NotBlank(message = "호실 이름을 입력해 주세요 (예: 101호)") @MaxChars(value = 20, message = "호실 이름은 20자 이하로 입력해 주세요") String name,
			@NotNull(message = "호실 유형을 골라 주세요") UnitType type,
			@MaxChars(value = 500, message = "메모는 500자 이하로 입력해 주세요") String memo) {

		static UnitForm empty() {
			return new UnitForm("", UnitType.ONE_ROOM, "");
		}

		UnitInput toInput() {
			return new UnitInput(name, type, memo);
		}

	}

	static String nullToEmpty(String value) {
		return value == null ? "" : value;
	}

}
