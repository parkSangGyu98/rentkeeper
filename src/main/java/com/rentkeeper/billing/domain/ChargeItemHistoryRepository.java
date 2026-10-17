package com.rentkeeper.billing.domain;

import java.util.List;

import org.springframework.data.repository.Repository;

/** 청구 항목 변경 이력 저장소. 추가만 한다 */
public interface ChargeItemHistoryRepository extends Repository<ChargeItemHistory, Long> {

	ChargeItemHistory save(ChargeItemHistory history);

	List<ChargeItemHistory> findByChargeIdAndLandlordIdOrderByIdAsc(long chargeId, long landlordId);

}
