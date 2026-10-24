package com.rentkeeper.lease.domain;

import java.util.Optional;

import org.springframework.data.repository.Repository;

/** 세입자 링크 토큰 저장소 (V17). 임대인 쪽 조회는 landlordId가 필요하다 (ADR-018) */
public interface TenantAccessTokenRepository extends Repository<TenantAccessToken, Long> {

	TenantAccessToken saveAndFlush(TenantAccessToken token);

	void flush();

	/** 계약의 그 유형 열린 링크 (계약·유형마다 하나, V20 ux_tenant_access_token_open) */
	Optional<TenantAccessToken> findByLeaseIdAndLandlordIdAndPurposeAndRevokedAtIsNull(long leaseId, long landlordId,
			TenantLinkPurpose purpose);

	/**
	 * 세입자가 들고 온 토큰의 해시로 찾는다 (세입자 화면의 입구, ADR-026). 세입자는 로그인하지 않아 landlordId가 없다. 이
	 * 메서드만 소유 저장소 규칙의 예외이고(OwnedRepositoryConventionTest), 찾은 행의
	 * landlordId·leaseId로 이후 조회를 모두 한정한다.
	 */
	Optional<TenantAccessToken> findByTokenHash(String tokenHash);

}
