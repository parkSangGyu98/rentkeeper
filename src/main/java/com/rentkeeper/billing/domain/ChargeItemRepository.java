package com.rentkeeper.billing.domain;

import java.util.List;
import java.util.Optional;

import org.springframework.data.repository.Repository;

/** 청구 항목 저장소. landlordId 없이 조회할 방법이 없다 (ADR-018) */
public interface ChargeItemRepository extends Repository<ChargeItem, Long> {

	ChargeItem saveAndFlush(ChargeItem item);

	List<ChargeItem> findByChargeIdAndLandlordIdOrderByIdAsc(long chargeId, long landlordId);

	/** 청구의 항목 하나 (다른 청구·다른 임대인의 항목이면 없음) */
	Optional<ChargeItem> findByIdAndChargeIdAndLandlordId(long id, long chargeId, long landlordId);

	void delete(ChargeItem item);

	void flush();

}
