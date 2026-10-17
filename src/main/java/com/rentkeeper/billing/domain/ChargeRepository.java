package com.rentkeeper.billing.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

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

	boolean existsByLeaseIdAndLandlordId(long leaseId, long landlordId);

	boolean existsByLeaseIdAndLandlordIdAndBillingMonth(long leaseId, long landlordId, LocalDate billingMonth);

	/** 계약의 가장 늦은 청구 달 (취소 포함, 그 달 1일) */
	@Query("select max(c.billingMonth) from Charge c where c.leaseId = :leaseId and c.landlordId = :landlordId")
	Optional<LocalDate> latestBillingMonth(@Param("leaseId") long leaseId, @Param("landlordId") long landlordId);

	/** 그 달 뒤의 청구 (달 순) */
	List<Charge> findByLeaseIdAndLandlordIdAndBillingMonthAfterOrderByBillingMonth(long leaseId, long landlordId,
			LocalDate billingMonth);

}
