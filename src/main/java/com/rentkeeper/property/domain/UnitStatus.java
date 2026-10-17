package com.rentkeeper.property.domain;

/**
 * 호실 상태 (PROP-3, M2 결정 1). 저장하지 않고 계약에서 계산한다(계산 규칙은 lease 모듈, ADR-019). 화면에서는
 * 색만으로 구분하지 않고 글자를 함께 보여 준다(PRD 6.3).
 */
public enum UnitStatus {

	/** 진행 중인 계약이 없다 */
	VACANT("공실"),

	/** 오늘이 계약 기간(들어오는 날 ~ 나가는 날 전날) 안이다 */
	OCCUPIED("입주중"),

	/** 들어오는 날이 아직 오지 않은 계약만 있다 */
	UPCOMING("입주 예정"),

	/** 나가는 날이 지났는데 종료 처리를 하지 않은 계약이 있다 */
	OVERDUE("기간 지남");

	private final String label;

	UnitStatus(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}

}
