package com.rentkeeper.lease.application;

import java.time.YearMonth;
import java.util.Optional;
import java.util.Set;

/**
 * 계약에 생긴 청구(받을 월세) 정보 (M3 필수 확인). lease는 billing에 기댈 수 없어 이 인터페이스를 정하고 billing이
 * 구현한다(LeaseChargedMonths, property의 UnitOccupancy와 같은 방식). 취소한 청구도 "생긴 달"로 센다:
 * 취소한 달은 다시 만들 수 없다(M3 결정 8).
 */
public interface ChargedMonths {

	/** 청구가 하나라도 있는지 */
	boolean any(long landlordId, long leaseId);

	/** 그 달 청구가 있는지 */
	boolean exists(long landlordId, long leaseId, YearMonth month);

	/** 청구가 있는 모든 달 (취소 포함) */
	Set<YearMonth> months(long landlordId, long leaseId);

	/** 청구가 있는 가장 늦은 달 */
	Optional<YearMonth> latest(long landlordId, long leaseId);

	/**
	 * 새 마지막 청구월 뒤 달의 청구 중 입금이 없는 것을 취소한다 (M3 결정 5). 호출한 쪽 트랜잭션 안에서, 계약 행을 잠근 뒤에
	 * 부른다(청구 생성 배치가 방금 만든 청구도 보이게, ADR-024).
	 *
	 * @param lastMonth
	 *            새 마지막 청구월. 빈 값이면(하루도 살지 않음) 모든 달
	 * @param reason
	 *            취소 사유 (개인정보 없이)
	 */
	Cancellation cancelAfter(long landlordId, long leaseId, Optional<YearMonth> lastMonth, String reason);

	/**
	 * @param canceled
	 *            취소한 청구 수
	 * @param keptWithPayments
	 *            입금 기록이 있어 취소하지 않은 청구 수 (임대인이 직접 확인해야 한다)
	 */
	record Cancellation(int canceled, int keptWithPayments) {

		public static final Cancellation NONE = new Cancellation(0, 0);

	}

}
