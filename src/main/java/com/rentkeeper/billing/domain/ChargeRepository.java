package com.rentkeeper.billing.domain;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

/** 청구 저장소. landlordId 없이 조회할 방법이 없다 (ADR-018) */
public interface ChargeRepository extends Repository<Charge, Long> {

	Charge saveAndFlush(Charge charge);

	void flush();

	Optional<Charge> findByIdAndLandlordId(long id, long landlordId);

	/** 임대인의 한 달 청구 (납부일 순) */
	List<Charge> findByLandlordIdAndBillingMonthOrderByDueDateAscIdAsc(long landlordId, LocalDate billingMonth);

	/** 계약에 이미 있는 청구 달 (취소 포함, 그 달 1일) */
	@Query("select c.billingMonth from Charge c where c.leaseId = :leaseId and c.landlordId = :landlordId")
	List<LocalDate> billingMonths(@Param("leaseId") long leaseId, @Param("landlordId") long landlordId);

	Optional<Charge> findByLeaseIdAndLandlordIdAndBillingMonth(long leaseId, long landlordId, LocalDate billingMonth);

	/**
	 * 세입자 링크가 가리키는 계약의 받을 월세 하나를 잠그고 가져온다 ("입금했어요", M4 결정 7). 다른 계약의 청구면 빈 값. 같은 순간
	 * 여러 번 눌러도 차례로 처리해 한 번만 반영한다.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select c from Charge c where c.id = :id and c.leaseId = :leaseId and c.landlordId = :landlordId")
	Optional<Charge> lockForTenant(@Param("id") long id, @Param("leaseId") long leaseId,
			@Param("landlordId") long landlordId);

	/** 세입자 링크가 가리키는 계약의 받을 월세 하나 (다른 계약의 청구면 빈 값) */
	Optional<Charge> findByIdAndLeaseIdAndLandlordId(long id, long leaseId, long landlordId);

	// ---------------------------------------------------------------- 대시보드
	// (DASH-1·2, M4 결정 14)

	/** 금액 합계 (원). balance는 청구마다 남은 금액(0 아래로 내려가지 않음)의 합 */
	interface Totals {

		long getCount();

		long getTotal();

		long getPaid();

		long getBalance();

	}

	String TOTALS = "select count(c) as count, coalesce(sum(c.totalAmount), 0) as total, "
			+ "coalesce(sum(c.paidAmount), 0) as paid, coalesce(sum(case when c.totalAmount > c.paidAmount "
			+ "then c.totalAmount - c.paidAmount else 0 end), 0) as balance from Charge c "
			+ "where c.landlordId = :landlordId ";

	/** 한 달 합계 (취소 제외) */
	@Query(TOTALS + "and c.billingMonth = :month and c.status <> com.rentkeeper.billing.domain.ChargeStatus.CANCELED")
	Totals monthTotals(@Param("landlordId") long landlordId, @Param("month") LocalDate month);

	@Query(TOTALS + "and c.billingMonth = :month and c.status <> com.rentkeeper.billing.domain.ChargeStatus.CANCELED "
			+ "and c.leaseId in :leaseIds")
	Totals monthTotalsOfLeases(@Param("landlordId") long landlordId, @Param("month") LocalDate month,
			@Param("leaseIds") Collection<Long> leaseIds);

	/** 한 상태의 합계 (모든 달) */
	@Query(TOTALS + "and c.status = :status")
	Totals statusTotals(@Param("landlordId") long landlordId, @Param("status") ChargeStatus status);

	@Query(TOTALS + "and c.status = :status and c.leaseId in :leaseIds")
	Totals statusTotalsOfLeases(@Param("landlordId") long landlordId, @Param("status") ChargeStatus status,
			@Param("leaseIds") Collection<Long> leaseIds);

	/** 세입자 신고: 먼저 알린 것부터 */
	List<Charge> findByLandlordIdAndStatusOrderByTenantReportedAtAscIdAsc(long landlordId, ChargeStatus status,
			Limit limit);

	List<Charge> findByLandlordIdAndStatusAndLeaseIdInOrderByTenantReportedAtAscIdAsc(long landlordId,
			ChargeStatus status, Collection<Long> leaseIds, Limit limit);

	/** 납부일 지남: 오래 지난 것부터 */
	List<Charge> findByLandlordIdAndStatusOrderByDueDateAscIdAsc(long landlordId, ChargeStatus status, Limit limit);

	List<Charge> findByLandlordIdAndStatusAndLeaseIdInOrderByDueDateAscIdAsc(long landlordId, ChargeStatus status,
			Collection<Long> leaseIds, Limit limit);

	/** 한 계약의 [from, to] 달 받을 월세, 달 순 (세입자 화면, M4 결정 6) */
	List<Charge> findByLeaseIdAndLandlordIdAndBillingMonthBetweenOrderByBillingMonthAsc(long leaseId, long landlordId,
			LocalDate from, LocalDate to);

	boolean existsByLeaseIdAndLandlordId(long leaseId, long landlordId);

	boolean existsByLeaseIdAndLandlordIdAndBillingMonth(long leaseId, long landlordId, LocalDate billingMonth);

	/** 계약의 가장 늦은 청구 달 (취소 포함, 그 달 1일) */
	@Query("select max(c.billingMonth) from Charge c where c.leaseId = :leaseId and c.landlordId = :landlordId")
	Optional<LocalDate> latestBillingMonth(@Param("leaseId") long leaseId, @Param("landlordId") long landlordId);

	/** 그 달 뒤의 청구 (달 순) */
	List<Charge> findByLeaseIdAndLandlordIdAndBillingMonthAfterOrderByBillingMonth(long leaseId, long landlordId,
			LocalDate billingMonth);

}
