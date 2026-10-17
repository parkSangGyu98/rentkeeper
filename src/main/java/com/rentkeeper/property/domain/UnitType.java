package com.rentkeeper.property.domain;

/** 호실 유형 (M2 결정 17, V13 ck_unit_type) */
public enum UnitType {

	ONE_ROOM("원룸"),

	TWO_ROOM("투룸"),

	THREE_PLUS("쓰리룸 이상"),

	STORE("상가"),

	OFFICE("사무실"),

	OTHER("기타");

	private final String label;

	UnitType(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}

}
