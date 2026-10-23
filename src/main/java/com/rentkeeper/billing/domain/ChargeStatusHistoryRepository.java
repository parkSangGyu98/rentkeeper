package com.rentkeeper.billing.domain;

import java.util.List;

import org.springframework.data.repository.Repository;

/** 청구 상태 이력 저장소. 추가만 한다 */
public interface ChargeStatusHistoryRepository extends Repository<ChargeStatusHistory, Long> {

	ChargeStatusHistory save(ChargeStatusHistory history);

	List<ChargeStatusHistory> findByChargeIdAndLandlordIdOrderByIdAsc(long chargeId, long landlordId);

}
