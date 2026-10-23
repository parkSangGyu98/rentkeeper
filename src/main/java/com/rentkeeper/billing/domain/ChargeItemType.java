package com.rentkeeper.billing.domain;

/** 청구 항목 종류 (CHG-3) */
public enum ChargeItemType {

	RENT("월세"),

	MAINTENANCE("관리비"),

	ELECTRIC("전기"),

	WATER("수도"),

	GAS("가스"),

	OTHER("기타");

	private final String label;

	ChargeItemType(String label) {
		this.label = label;
	}

	/** 기본 항목 이름 */
	public String getLabel() {
		return label;
	}

}
