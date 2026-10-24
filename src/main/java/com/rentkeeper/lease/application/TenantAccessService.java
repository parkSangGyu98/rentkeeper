package com.rentkeeper.lease.application;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.rentkeeper.common.crypto.Masking;
import com.rentkeeper.common.crypto.SecureTokens;
import com.rentkeeper.lease.domain.Lease;
import com.rentkeeper.lease.domain.LeaseRepository;
import com.rentkeeper.lease.domain.Tenant;
import com.rentkeeper.lease.domain.TenantAccessToken;
import com.rentkeeper.lease.domain.TenantAccessTokenRepository;
import com.rentkeeper.lease.domain.TenantRepository;
import com.rentkeeper.property.application.PropertyService;

/**
 * 세입자 링크의 입구 (TEN-1, ADR-026). 세입자가 들고 온 토큰을 확인해 그 링크가 가리키는 계약 하나로 범위를 정한다. 이후
 * 모든 조회는 여기서 받은 {@link TenantAccess}의 landlordId·leaseId로만 한다.
 * <p>
 * 형식이 틀린 토큰, 없는 토큰, 닫힌 링크, 기간이 지난 링크는 모두 같은 빈 값이다(어느 경우인지 화면에 드러내지 않는다).
 */
@Service
@Transactional(readOnly = true)
public class TenantAccessService {

	private final TenantAccessTokenRepository tokens;

	private final LeaseRepository leases;

	private final TenantRepository tenants;

	private final PropertyService propertyService;

	private final Clock clock;

	TenantAccessService(TenantAccessTokenRepository tokens, LeaseRepository leases, TenantRepository tenants,
			PropertyService propertyService, Clock clock) {
		this.tokens = tokens;
		this.leases = leases;
		this.tenants = tenants;
		this.propertyService = propertyService;
		this.clock = clock;
	}

	/**
	 * 확인된 세입자 링크. 원문 토큰은 담지 않는다.
	 *
	 * @param tokenId
	 *            링크 ID (세입자가 한 일의 이력 actor_id, 레이트 리미트 키)
	 */
	public record TenantAccess(long tokenId, long landlordId, long leaseId) {
	}

	/**
	 * 세입자 화면에 보일 계약 정보 (M4 결정 5). 이름·예금주는 가린 값만 담는다. 전화번호·보증금·메모는 담지 않는다.
	 *
	 * @param accountNumber
	 *            계좌번호 전체 (입금에 필요)
	 */
	public record TenantLease(String unitName, String maskedTenantName, String bankName, String accountNumber,
			String maskedAccountHolder) {

		@Override
		public String toString() {
			return "TenantLease[***]";
		}

	}

	/**
	 * 토큰을 확인하고, 쓸 수 있으면 마지막으로 연 날을 기록한다(하루에 한 번).
	 *
	 * @return 쓸 수 없는 링크면 빈 값
	 */
	@Transactional
	public Optional<TenantAccess> open(String rawToken) {
		if (!SecureTokens.isWellFormed(rawToken)) {
			return Optional.empty();
		}
		Optional<TenantAccessToken> found = tokens.findByTokenHash(SecureTokens.sha256Hex(rawToken));
		if (found.isEmpty()) {
			return Optional.empty();
		}
		TenantAccessToken token = found.get();
		Optional<Lease> lease = leases.findByIdAndLandlordId(token.getLeaseId(), token.getLandlordId());
		if (lease.isEmpty()
				|| !token.usableOn(LocalDate.now(clock), lease.get().getEndDate(), lease.get().getEndedOn())) {
			return Optional.empty();
		}
		if (token.markUsed(clock.instant(), clock.getZone())) {
			tokens.flush();
		}
		return Optional.of(new TenantAccess(token.getId(), token.getLandlordId(), token.getLeaseId()));
	}

	public TenantLease lease(TenantAccess access) {
		Lease lease = leases.findByIdAndLandlordId(access.leaseId(), access.landlordId()).orElseThrow();
		Tenant tenant = tenants.findByIdAndLandlordId(lease.getTenantId(), access.landlordId()).orElseThrow();
		String unitName = propertyService.unit(access.landlordId(), lease.getUnitId()).displayName();
		return new TenantLease(unitName, Masking.name(tenant.getName().value()), lease.getBankName(),
				lease.getAccountNumber().digits(), Masking.name(lease.getAccountHolder().value()));
	}

}
