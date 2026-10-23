package com.rentkeeper.billing.domain;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** 청구 상태 이력 저장소. 추가만 한다 */
public interface ChargeStatusHistoryRepository extends Repository<ChargeStatusHistory, Long> {

	ChargeStatusHistory save(ChargeStatusHistory history);

	List<ChargeStatusHistory> findByChargeIdAndLandlordIdOrderByIdAsc(long chargeId, long landlordId);

	/** 청구별 건수 */
	interface ChargeCount {

		long getChargeId();

		long getCount();

	}

	/** 청구들이 세입자 신고(TENANT_REPORTED)로 바뀐 횟수 ("N번째 신고", M4 결정 7) */
	@Query("""
			select h.chargeId as chargeId, count(h) as count from ChargeStatusHistory h
			where h.landlordId = :landlordId and h.chargeId in :chargeIds
			  and h.toStatus = com.rentkeeper.billing.domain.ChargeStatus.TENANT_REPORTED
			group by h.chargeId
			""")
	List<ChargeCount> reportCounts(@Param("landlordId") long landlordId,
			@Param("chargeIds") Collection<Long> chargeIds);

}
