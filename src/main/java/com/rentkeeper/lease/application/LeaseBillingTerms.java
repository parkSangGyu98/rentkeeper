package com.rentkeeper.lease.application;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 청구 계산에 필요한 계약 사실 (billing 모듈이 읽는다, ADR-024). 계약 엔티티를 넘기지 않고 값만 넘긴다.
 *
 * @param actualEnd
 *            실제로 끝나는 날 (기간 밖, [) 범위): 종료했으면 끝난 날(ended_on), 아니면 나가는 날(end_date)
 * @param registeredOn
 *            계약을 등록한 날 (서울 날짜). 이 날 이후가 납부일인 달부터 자동으로 청구한다(M3 결정 2)
 * @param rents
 *            월세 이력 (적용 시작월 순)
 */
public record LeaseBillingTerms(long leaseId, long landlordId, int dueDay, LocalDate startDate, LocalDate actualEnd,
		int issueDaysBefore, LocalDate registeredOn, List<Rent> rents) {

	/** 적용 시작월부터의 월세·관리비 (원) */
	public record Rent(YearMonth from, long monthlyRent, long maintenanceFee) {
	}

	public LeaseBillingTerms {
		rents = rents.stream().sorted(Comparator.comparing(Rent::from)).toList();
	}

	/** 들어오는 달 */
	public YearMonth firstMonth() {
		return YearMonth.from(startDate);
	}

	/** 마지막 청구월 = (실제 끝 − 하루)가 속한 달 (M3 결정 4). 하루도 살지 않은 계약이면 빈 값 */
	public Optional<YearMonth> lastMonth() {
		if (!actualEnd.isAfter(startDate)) {
			return Optional.empty();
		}
		return Optional.of(YearMonth.from(actualEnd.minusDays(1)));
	}

	/** 그 달의 금액 = 그 달 이하에서 가장 최근 이력 (M2 결정 5) */
	public Optional<Rent> rentFor(YearMonth month) {
		Rent found = null;
		for (Rent rent : rents) {
			if (!rent.from().isAfter(month)) {
				found = rent;
			}
		}
		return Optional.ofNullable(found);
	}

}
