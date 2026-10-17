package com.rentkeeper.lease.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.rentkeeper.common.crypto.KeyedHasher;
import com.rentkeeper.common.error.ResourceNotFoundException;
import com.rentkeeper.common.jpa.Constraints;
import com.rentkeeper.lease.application.LeaseExceptions.ExistingTenantException;
import com.rentkeeper.lease.application.LeaseExceptions.LeaseOverlapException;
import com.rentkeeper.lease.application.LeaseViews.AccountPrefill;
import com.rentkeeper.lease.application.LeaseViews.ExistingTenant;
import com.rentkeeper.lease.application.LeaseViews.LeaseDetail;
import com.rentkeeper.lease.application.LeaseViews.LeaseRegistration;
import com.rentkeeper.lease.application.LeaseViews.LeaseRow;
import com.rentkeeper.lease.application.LeaseViews.RentRow;
import com.rentkeeper.lease.application.LeaseViews.UnitPage;
import com.rentkeeper.lease.domain.Lease;
import com.rentkeeper.lease.domain.LeaseRentHistory;
import com.rentkeeper.lease.domain.LeaseRentHistoryRepository;
import com.rentkeeper.lease.domain.LeaseRepository;
import com.rentkeeper.lease.domain.PhoneNumber;
import com.rentkeeper.lease.domain.Tenant;
import com.rentkeeper.lease.domain.TenantRepository;
import com.rentkeeper.lease.domain.UnitOccupancyRule;
import com.rentkeeper.lease.domain.UnitOccupancyRule.Occupancy;
import com.rentkeeper.property.application.PropertyService;
import com.rentkeeper.property.application.PropertyViews.UnitView;

/**
 * 세입자·계약 등록과 조회 (LEASE-1, LEASE-4). 모든 공개 메서드는 첫 인자로 로그인한 임대인의 landlordId를 받고,
 * 남의 것이거나 없는 리소스는 같은 404로 끝낸다 (ADR-018). 오늘 날짜는 주입한 Clock(서울)으로 정한다 (ADR-002).
 */
@Service
@Transactional(readOnly = true)
public class LeaseService {

	static final String PHONE_PURPOSE = "phone";

	static final String OVERLAP_CONSTRAINT = "ex_lease_unit_active_period";

	static final String TENANT_PHONE_UNIQUE = "uk_tenant_landlord_phone";

	private final LeaseRepository leases;

	private final TenantRepository tenants;

	private final LeaseRentHistoryRepository histories;

	private final PropertyService propertyService;

	private final KeyedHasher hasher;

	private final ChargedMonths chargedMonths;

	private final Clock clock;

	public LeaseService(LeaseRepository leases, TenantRepository tenants, LeaseRentHistoryRepository histories,
			PropertyService propertyService, KeyedHasher hasher, ChargedMonths chargedMonths, Clock clock) {
		this.leases = leases;
		this.tenants = tenants;
		this.histories = histories;
		this.propertyService = propertyService;
		this.hasher = hasher;
		this.chargedMonths = chargedMonths;
		this.clock = clock;
	}

	// ---------------------------------------------------------------- 등록

	/** 계약을 등록할 호실 (남의 호실이면 404) */
	public UnitView unitForNewLease(long landlordId, long unitId) {
		return propertyService.unit(landlordId, unitId);
	}

	/** 같은 임대인의 직전 계약 계좌로 미리 채운다 (M2 결정 20) */
	public Optional<AccountPrefill> accountPrefill(long landlordId) {
		return leases.findFirstByLandlordIdOrderByIdDesc(landlordId)
				.map(l -> AccountPrefill.of(l.getBankName(), l.getAccountNumber(), l.getAccountHolder()));
	}

	/** 같은 연락처로 이미 등록된 세입자 */
	public Optional<ExistingTenant> existingTenant(long landlordId, PhoneNumber phone) {
		return tenants.findByLandlordIdAndPhoneHash(landlordId, phoneHash(phone)).map(LeaseService::existingView);
	}

	/**
	 * @throws LeaseOverlapException
	 *             같은 호실의 진행 중 계약과 기간이 겹칠 때
	 */
	public void checkOverlap(long landlordId, long unitId, LocalDate start, LocalDate end) {
		if (leases.existsActiveOverlap(landlordId, unitId, start, end, 0L)) {
			throw new LeaseOverlapException();
		}
	}

	/**
	 * 계약과 첫 월세 이력을 만든다. 같은 연락처의 세입자가 있으면 확인(useExistingTenant)을 받은 뒤에만 그 세입자로 계약한다.
	 * 이때 입력한 이름·메모로 기존 세입자 정보를 바꾸지 않는다(세입자 정보 수정은 계약 수정에서).
	 *
	 * @throws LeaseOverlapException
	 *             기간이 겹칠 때 (동시 요청은 DB 제약이 막는다)
	 * @throws ExistingTenantException
	 *             같은 연락처의 세입자가 있는데 확인을 받지 않았을 때
	 */
	@Transactional
	public long register(long landlordId, LeaseRegistration registration) {
		// 같은 호실의 동시 등록을 차례로 처리한다: 겹침 확인이 정확해지고, EXCLUDE 제약끼리의 교착을 막는다 (ADR-019)
		UnitView unit = propertyService.lockUnit(landlordId, registration.unitId());
		checkOverlap(landlordId, unit.id(), registration.terms().startDate(), registration.terms().endDate());

		String hash = phoneHash(registration.tenantPhone());
		Tenant tenant = tenants.findByLandlordIdAndPhoneHash(landlordId, hash).orElse(null);
		if (tenant != null && !registration.useExistingTenant()) {
			throw new ExistingTenantException(existingView(tenant));
		}
		if (tenant == null) {
			try {
				tenant = tenants.saveAndFlush(Tenant.register(landlordId, registration.tenantName(),
						registration.tenantPhone(), hash, registration.tenantMemo()));
			} catch (DataIntegrityViolationException e) {
				if (Constraints.isViolated(e, TENANT_PHONE_UNIQUE)) {
					// 다른 창에서 같은 번호로 먼저 등록됐다 → 다시 확인받는다
					throw new ExistingTenantException(null);
				}
				throw e;
			}
		}

		Lease lease;
		try {
			lease = leases.saveAndFlush(Lease.register(landlordId, unit.id(), tenant.getId(), registration.terms()));
		} catch (DataIntegrityViolationException e) {
			if (Constraints.isViolated(e, OVERLAP_CONSTRAINT)) {
				throw new LeaseOverlapException(e);
			}
			throw e;
		}
		histories.saveAndFlush(
				LeaseRentHistory.initial(lease, registration.monthlyRent(), registration.maintenanceFee()));
		return lease.getId();
	}

	// ---------------------------------------------------------------- 조회

	public LeaseDetail detail(long landlordId, long leaseId) {
		Lease lease = ownedLease(landlordId, leaseId);
		Tenant tenant = tenants.findByIdAndLandlordId(lease.getTenantId(), landlordId)
				.orElseThrow(() -> new ResourceNotFoundException("tenant"));
		UnitView unit = propertyService.unit(landlordId, lease.getUnitId());
		YearMonth thisMonth = YearMonth.now(clock);
		List<LeaseRentHistory> history = histories.findByLeaseIdAndLandlordIdOrderByEffectiveFromAsc(leaseId,
				landlordId);
		// 청구가 생긴 달 이하의 변경은 취소할 수 없다 (M3 결정 13)
		Optional<YearMonth> lastCharged = chargedMonths.latest(landlordId, leaseId);
		List<RentRow> rows = history.stream()
				.map(h -> new RentRow(h.getId(), h.effectiveMonth(), h.getMonthlyRent(), h.getMaintenanceFee(),
						h.getReason(), lease.isActive() && h != history.get(0) && h.effectiveMonth().isAfter(thisMonth)
								&& lastCharged.map(m -> h.effectiveMonth().isAfter(m)).orElse(true)))
				.toList();
		// 이번 달 금액: 이번 달 이하에서 가장 최근 행. 아직 들어오기 전 계약이면 첫 행
		RentRow current = rows.stream().filter(r -> !r.effectiveMonth().isAfter(thisMonth))
				.reduce((first, second) -> second).orElse(rows.isEmpty() ? null : rows.get(0));
		List<RentRow> upcoming = rows.stream().filter(r -> r.effectiveMonth().isAfter(thisMonth))
				.filter(r -> r != current).toList();
		return new LeaseDetail(lease.getId(), unit, tenant.getName().value(), tenant.getPhone().formatted(),
				tenant.getMemo() == null ? null : tenant.getMemo().value(), lease.getDeposit(), lease.getDueDay(),
				lease.getStartDate(), lease.getEndDate(), lease.getStatus(), lease.getEndedOn(), lease.getBankName(),
				lease.getAccountNumber().digits(), lease.getAccountHolder().value(), lease.getMemo(), current, rows,
				upcoming);
	}

	/**
	 * 진행 중인 계약의 상세 (종료 확인 화면 등)
	 *
	 * @throws com.rentkeeper.lease.application.LeaseExceptions.LeaseEndedException
	 *             종료한 계약
	 */
	public LeaseDetail activeDetail(long landlordId, long leaseId) {
		LeaseDetail detail = detail(landlordId, leaseId);
		if (!detail.active()) {
			throw new LeaseExceptions.LeaseEndedException();
		}
		return detail;
	}

	/** 호실 화면: 상태, 현재 계약, 지난 계약까지 모두 (ADR-019) */
	public UnitPage unitPage(long landlordId, long unitId) {
		UnitView unit = propertyService.unit(landlordId, unitId);
		List<Lease> unitLeases = leases.findByUnitIdAndLandlordIdOrderByStartDateDesc(unitId, landlordId);
		Occupancy occupancy = UnitOccupancyRule.evaluate(unitLeases, today());
		Map<Long, Tenant> tenantById = tenants
				.findByLandlordIdAndIdIn(landlordId, unitLeases.stream().map(Lease::getTenantId).distinct().toList())
				.stream().collect(Collectors.toMap(Tenant::getId, Function.identity()));
		List<LeaseRow> rows = unitLeases.stream()
				.map(l -> new LeaseRow(l.getId(), tenantById.get(l.getTenantId()).getName().value(), l.getStartDate(),
						l.getEndDate(), l.getStatus(), l.getEndedOn(), l.getId().equals(occupancy.currentLeaseId())))
				.sorted(Comparator.comparing(LeaseRow::current).reversed()).toList();
		return new UnitPage(unit, occupancy.status(), occupancy.staleActive(), occupancy.currentLeaseId(), rows);
	}

	/** 오늘 (서울 날짜). 기간 비교 같은 업무 날짜는 LocalDate로 한다 (ADR-002) */
	public LocalDate today() {
		return LocalDate.now(clock);
	}

	private Lease ownedLease(long landlordId, long leaseId) {
		return leases.findByIdAndLandlordId(leaseId, landlordId)
				.orElseThrow(() -> new ResourceNotFoundException("lease"));
	}

	private String phoneHash(PhoneNumber phone) {
		return hasher.hash(PHONE_PURPOSE, phone.digits());
	}

	private static ExistingTenant existingView(Tenant tenant) {
		return new ExistingTenant(tenant.getName().value(), tenant.getPhone().formatted());
	}

}
