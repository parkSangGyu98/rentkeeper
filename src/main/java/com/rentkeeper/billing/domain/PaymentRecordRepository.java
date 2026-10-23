package com.rentkeeper.billing.domain;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** 입금 기록 저장소. landlordId 없이 조회할 방법이 없다 (ADR-018) */
public interface PaymentRecordRepository extends Repository<PaymentRecord, Long> {

	PaymentRecord saveAndFlush(PaymentRecord record);

	/** 청구의 입금 기록 하나 (다른 청구·다른 임대인의 기록이면 없음) */
	Optional<PaymentRecord> findByIdAndChargeIdAndLandlordId(long id, long chargeId, long landlordId);

	/** 청구의 모든 입금 기록 (지운 것 포함, 기록 순) */
	List<PaymentRecord> findByChargeIdAndLandlordIdOrderByIdAsc(long chargeId, long landlordId);

	/** 청구의 받은 금액 합계 = 지우지 않은 기록의 합 (원) */
	@Query("""
			select coalesce(sum(p.amount), 0) from PaymentRecord p
			where p.chargeId = :chargeId and p.landlordId = :landlordId and p.deletedAt is null
			""")
	long activeTotal(@Param("chargeId") long chargeId, @Param("landlordId") long landlordId);

}
