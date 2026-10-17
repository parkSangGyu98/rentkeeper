package com.rentkeeper.lease.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.repository.Repository;

/** 세입자 저장소. landlordId 없이 조회할 방법이 없다 (ADR-018) */
public interface TenantRepository extends Repository<Tenant, Long> {

	Tenant saveAndFlush(Tenant tenant);

	void flush();

	Optional<Tenant> findByIdAndLandlordId(long id, long landlordId);

	/** 같은 임대인 안에서 같은 연락처(HMAC)의 세입자 (M2 결정 6) */
	Optional<Tenant> findByLandlordIdAndPhoneHash(long landlordId, String phoneHash);

	List<Tenant> findByLandlordIdAndIdIn(long landlordId, Collection<Long> ids);

}
