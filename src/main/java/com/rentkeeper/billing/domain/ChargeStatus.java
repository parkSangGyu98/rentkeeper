package com.rentkeeper.billing.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * 청구 상태 (PRD 4.4.2, ADR-023). 상태는 직접 정하지 않고 사실(취소 여부, 받은 금액 대 총액, 납부일 대 오늘, 세입자
 * 신고, 발행 여부)에서 계산한다(Charge). 계산 결과가 바뀌면 {@link #canTransitionTo}로 검사한 뒤 이력을
 * 남긴다.
 *
 * <pre>
 * 지금 \ 다음        SCHEDULED ISSUED TENANT_REPORTED PARTIALLY_PAID PAID OVERDUE CANCELED
 * SCHEDULED             -       발행        ✗            입금        입금     ✗      취소
 * ISSUED                ✗        -         신고           입금        입금  납부일지남   취소
 * TENANT_REPORTED       ✗     신고반려       -            입금        입금  신고반려*   취소
 * PARTIALLY_PAID        ✗     입금삭제    나머지신고        -          입금  납부일지남    ✗
 * PAID                  ✗     입금삭제       ✗        입금삭제·총액증가   -    입금삭제*    ✗
 * OVERDUE               ✗     납부일수정    신고         납부일수정     입금     -       취소
 * CANCELED              ✗        ✗          ✗             ✗          ✗      ✗        -
 * </pre>
 *
 * TENANT_REPORTED → OVERDUE는 납부일이 지난 뒤 임대인이 신고를 반려할 때만 생긴다. 납부일 지남 배치는 신고 상태를
 * 건드리지 않는다(M3 결정 7). PAID → OVERDUE는 납부일이 지난 뒤 입금을 지워 덜 받게 된 경우다. 취소는 입금이 없을
 * 때만(Charge).
 */
public enum ChargeStatus {

	SCHEDULED("준비 중"),

	ISSUED("받을 차례"),

	TENANT_REPORTED("확인 기다림"),

	PARTIALLY_PAID("일부 받음"),

	PAID("다 받음"),

	OVERDUE("납부일 지남"),

	CANCELED("취소함");

	private final String label;

	ChargeStatus(String label) {
		this.label = label;
	}

	/** 화면에 보일 이름 */
	public String getLabel() {
		return label;
	}

	/** 이 상태에서 next로 바뀔 수 있는지. 같은 상태로는 바뀌지 않는다(false) */
	public boolean canTransitionTo(ChargeStatus next) {
		return nextStates().contains(next);
	}

	private Set<ChargeStatus> nextStates() {
		return switch (this) {
			case SCHEDULED -> EnumSet.of(ISSUED, PARTIALLY_PAID, PAID, CANCELED);
			case ISSUED -> EnumSet.of(TENANT_REPORTED, PARTIALLY_PAID, PAID, OVERDUE, CANCELED);
			case TENANT_REPORTED -> EnumSet.of(ISSUED, PARTIALLY_PAID, PAID, OVERDUE, CANCELED);
			case PARTIALLY_PAID -> EnumSet.of(ISSUED, TENANT_REPORTED, PAID, OVERDUE);
			case PAID -> EnumSet.of(ISSUED, PARTIALLY_PAID, OVERDUE);
			case OVERDUE -> EnumSet.of(ISSUED, TENANT_REPORTED, PARTIALLY_PAID, PAID, CANCELED);
			case CANCELED -> EnumSet.noneOf(ChargeStatus.class);
		};
	}

	/** 더 받을 돈이 남아 납부일 지남 배치가 볼 상태. TENANT_REPORTED는 빠진다(M3 결정 7) */
	/** 납부일 전날 안내 대상 (M5 결정 5): 아직 다 받지 않았고 세입자 신고도 없는 것 */
	public static Set<ChargeStatus> dueReminderTargets() {
		return EnumSet.of(ISSUED, PARTIALLY_PAID);
	}

	/**
	 * 납부일 지남 안내 대상 (M5 결정 5): 납부일 지남만. 세입자가 입금했다고 알린 것(TENANT_REPORTED)은 빠진다(M3 결정
	 * 7과 같은 기준)
	 */
	public static Set<ChargeStatus> overdueNoticeTargets() {
		return EnumSet.of(OVERDUE);
	}

	public static Set<ChargeStatus> overdueCandidates() {
		return EnumSet.of(ISSUED, PARTIALLY_PAID);
	}

}
