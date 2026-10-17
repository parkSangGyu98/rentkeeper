package com.rentkeeper.billing.domain;

import java.util.Optional;

import org.springframework.data.repository.Repository;

/** 청구 저장소. landlordId 없이 조회할 방법이 없다 (ADR-018) */
public interface ChargeRepository extends Repository<Charge, Long> {

	Charge saveAndFlush(Charge charge);

	void flush();

	Optional<Charge> findByIdAndLandlordId(long id, long landlordId);

}
