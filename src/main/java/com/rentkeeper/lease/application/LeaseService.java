package com.rentkeeper.lease.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
import com.rentkeeper.lease.application.LeaseViews.EndingLease;
import com.rentkeeper.lease.application.LeaseViews.EndingLeases;
import com.rentkeeper.lease.application.LeaseViews.ExistingTenant;
import com.rentkeeper.lease.application.LeaseViews.LeaseDetail;
import com.rentkeeper.lease.application.LeaseViews.LeaseRegistration;
import com.rentkeeper.lease.application.LeaseViews.LeaseRow;
import com.rentkeeper.lease.application.LeaseViews.LeaseSummary;
import com.rentkeeper.lease.application.LeaseViews.MissingCharges;
import com.rentkeeper.lease.application.LeaseViews.RentRow;
import com.rentkeeper.lease.application.LeaseViews.UnitPage;
import com.rentkeeper.lease.domain.Lease;
import com.rentkeeper.lease.domain.LeaseRentHistory;
import com.rentkeeper.lease.domain.LeaseRentHistoryRepository;
import com.rentkeeper.lease.domain.LeaseRepository;
import com.rentkeeper.lease.domain.LeaseTerms;
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
			Lease draft = Lease.register(landlordId, unit.id(), tenant.getId(), registration.terms());
			draft.changeIssueDaysBefore(registration.issueDaysBefore());
			lease = leases.saveAndFlush(draft);
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
				tenant.getMemo() == null ? null : tenant.getMemo().value(), tenant.notificationsOptedOut(),
				lease.getDeposit(), lease.getDueDay(), lease.getStartDate(), lease.getEndDate(), lease.getStatus(),
				lease.getEndedOn(), lease.getBankName(), lease.getAccountNumber().digits(),
				lease.getAccountHolder().value(), lease.getMemo(), current, rows, upcoming, lease.getIssueDaysBefore(),
				missingCharges(landlordId, lease, thisMonth));
	}

	/** 계약 상세 배너에서 지난 달을 몇 달까지 보는지 (M3-b 결정 11). 그보다 오래된 달은 수동 생성 화면에서 고른다 */
	public static final int RECENT_MISSING_MONTHS = 3;

	/**
	 * 받을 월세가 없는 달 (계약 상세 배너, M3-b 결정 11). 진행 중 계약만 본다. 취소한 달도 "있음"으로 센다(다시 만들 수
	 * 없다).
	 * <ul>
	 * <li>이번 달: 계약 기간 안이고, 납부일이 오늘보다 앞인데 받을 월세가 없다 (등록 전에 납부일이 지났거나 배치가 실패함)</li>
	 * <li>지난 달: 이번 달 앞 {@value #RECENT_MISSING_MONTHS}개 달 중 계약 기간 안인데 받을 월세가 없는
	 * 달</li>
	 * </ul>
	 */
	private MissingCharges missingCharges(long landlordId, Lease lease, YearMonth thisMonth) {
		if (!lease.isActive() || !lease.getEndDate().isAfter(lease.getStartDate())) {
			return MissingCharges.NONE;
		}
		Set<YearMonth> charged = chargedMonths.months(landlordId, lease.getId());
		YearMonth first = YearMonth.from(lease.getStartDate());
		YearMonth last = lease.lastMonth();
		LocalDate due = Lease.dueDateIn(thisMonth, lease.getDueDay());
		boolean thisMonthMissing = !thisMonth.isBefore(first) && !thisMonth.isAfter(last)
				&& !charged.contains(thisMonth) && due.isBefore(today());
		List<YearMonth> recent = new ArrayList<>();
		for (YearMonth m = thisMonth.minusMonths(RECENT_MISSING_MONTHS); m.isBefore(thisMonth); m = m.plusMonths(1)) {
			if (!m.isBefore(first) && !m.isAfter(last) && !charged.contains(m)) {
				recent.add(m);
			}
		}
		return new MissingCharges(thisMonthMissing ? due : null, recent);
	}

	/** 지금 등록하면 이번 달 받을 월세가 자동으로 만들어지지 않는지 (등록 확인 화면의 안내, M3 결정 2) */
	public boolean thisMonthNotAutomatic(LeaseTerms terms) {
		return Lease.registrationMonthSkipped(terms.startDate(), terms.endDate(), terms.dueDay(), today());
	}

	/**
	 * 여러 계약의 요약 (받을 월세 목록 등, billing이 쓴다). 남의 계약 ID는 결과에서 빠진다(ADR-018). 세입자 이름은
	 * 복호화한다.
	 */
	public Map<Long, LeaseSummary> summaries(long landlordId, Collection<Long> leaseIds) {
		if (leaseIds.isEmpty()) {
			return Map.of();
		}
		List<Lease> found = leases.findByLandlordIdAndIdIn(landlordId, leaseIds);
		Map<Long, UnitView> unitViews = propertyService.units(landlordId,
				found.stream().map(Lease::getUnitId).collect(Collectors.toSet()));
		Map<Long, Tenant> tenantById = tenants
				.findByLandlordIdAndIdIn(landlordId, found.stream().map(Lease::getTenantId).collect(Collectors.toSet()))
				.stream().collect(Collectors.toMap(Tenant::getId, Function.identity()));
		return found.stream()
				.collect(Collectors.toMap(Lease::getId,
						l -> new LeaseSummary(l.getId(), l.getUnitId(), unitViews.get(l.getUnitId()).displayName(),
								tenantById.get(l.getTenantId()).getName().value(), l.getStartDate())));
	}

	/** 호실들의 모든 계약 ID (종료 포함, 대시보드의 건물 고르기). 남의 호실 ID는 결과에 없다 */
	public List<Long> leaseIdsOfUnits(long landlordId, Collection<Long> unitIds) {
		return unitIds.isEmpty() ? List.of() : leases.findIdsByLandlordIdAndUnitIdIn(landlordId, unitIds);
	}

	/**
	 * 나가는 날이 오늘부터 days일 안인 진행 중 계약 (DASH-3, M4 결정 14). 나가는 날이 이미 지났는데 종료하지 않은 계약도
	 * 들어간다(종료 처리가 필요). 나가는 날 순, 이름은 보이는 limit건만 복호화한다.
	 *
	 * @param unitIds
	 *            이 호실들만 (null이면 전체)
	 */
	public EndingLeases endingSoon(long landlordId, Collection<Long> unitIds, int days, int limit) {
		LocalDate today = today();
		LocalDate until = today.plusDays(days);
		List<Lease> found;
		if (unitIds == null) {
			found = leases.findActiveEndingBy(landlordId, until);
		} else {
			found = unitIds.isEmpty() ? List.of() : leases.findActiveEndingByInUnits(landlordId, until, unitIds);
		}
		List<Lease> shown = found.stream().limit(limit).toList();
		Map<Long, LeaseSummary> names = summaries(landlordId, shown.stream().map(Lease::getId).toList());
		List<EndingLease> rows = shown.stream().map(l -> new EndingLease(l.getId(), names.get(l.getId()).unitName(),
				names.get(l.getId()).tenantName(), l.getEndDate(), ChronoUnit.DAYS.between(today, l.getEndDate())))
				.toList();
		return new EndingLeases(found.size(), rows);
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
