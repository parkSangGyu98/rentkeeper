package com.rentkeeper.lease.domain;

import java.util.Optional;

import org.springframework.data.repository.Repository;

/** 세입자 링크 토큰 저장소 (V17). 임대인 쪽 조회는 landlordId가 필요하다 (ADR-018) */
public interface TenantAccessTokenRepository extends Repository<TenantAccessToken, Long> {

	TenantAccessToken saveAndFlush(TenantAccessToken token);

	void flush();

	/** 계약의 열린 링크 (계약당 하나, V17 ux_tenant_access_token_open) */
	Optional<TenantAccessToken> findByLeaseIdAndLandlordIdAndRevokedAtIsNull(long leaseId, long landlordId);

}
