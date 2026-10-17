package com.rentkeeper.billing.domain;

import java.util.List;

import org.springframework.data.repository.Repository;

/** 청구 항목 저장소. landlordId 없이 조회할 방법이 없다 (ADR-018) */
public interface ChargeItemRepository extends Repository<ChargeItem, Long> {

	ChargeItem saveAndFlush(ChargeItem item);

	List<ChargeItem> findByChargeIdAndLandlordIdOrderByIdAsc(long chargeId, long landlordId);

}
