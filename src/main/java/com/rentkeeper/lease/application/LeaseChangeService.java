package com.rentkeeper.lease.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.rentkeeper.common.crypto.KeyedHasher;
import com.rentkeeper.common.error.ResourceNotFoundException;
import com.rentkeeper.common.jpa.Constraints;
import com.rentkeeper.lease.application.LeaseExceptions.ChangeAfterEndException;
import com.rentkeeper.lease.application.LeaseExceptions.DuplicateTenantPhoneException;
import com.rentkeeper.lease.application.LeaseExceptions.LeaseEndedException;
import com.rentkeeper.lease.application.LeaseExceptions.LeaseOverlapException;
import com.rentkeeper.lease.application.LeaseViews.RentRow;
import com.rentkeeper.lease.domain.AccountNumber;
import com.rentkeeper.lease.domain.Lease;
import com.rentkeeper.lease.domain.LeaseRentHistory;
import com.rentkeeper.lease.domain.LeaseRentHistoryRepository;
import com.rentkeeper.lease.domain.LeaseRepository;
import com.rentkeeper.lease.domain.LeaseTerms;
import com.rentkeeper.lease.domain.PersonName;
import com.rentkeeper.lease.domain.PhoneNumber;
import com.rentkeeper.lease.domain.PrivateMemo;
import com.rentkeeper.lease.domain.RentSchedule;
import com.rentkeeper.lease.domain.Tenant;
import com.rentkeeper.lease.domain.TenantRepository;
import com.rentkeeper.property.application.PropertyService;

/**
 * 계약 수정·월세 변경·계약 종료·삭제 (LEASE-2, LEASE-3, M2 결정 3·4·11·12). 상태 변경은 도메인
 * 메서드(Lease, RentSchedule)로만 한다. 오늘·이번 달은 주입한 Clock(서울)으로 정한다. 남의 계약이면 404
 * (ADR-018).
 */
@Service
@Transactional(readOnly = true)
public class LeaseChangeService {

	private final LeaseRepository leases;

	private final TenantRepository tenants;

	private final LeaseRentHistoryRepository histories;

	private final PropertyService propertyService;

	private final KeyedHasher hasher;

	private final Clock clock;

	public LeaseChangeService(LeaseRepository leases, TenantRepository tenants, LeaseRentHistoryRepository histories,
			PropertyService propertyService, KeyedHasher hasher, Clock clock) {
		this.leases = leases;
		this.tenants = tenants;
		this.histories = histories;
		this.propertyService = propertyService;
		this.hasher = hasher;
		this.clock = clock;
	}

	/** 계약 수정 입력 (검증을 마친 값) */
	public record LeaseEdit(PersonName tenantName, PhoneNumber tenantPhone, PrivateMemo tenantMemo, long deposit,
			int dueDay, LocalDate endDate, String bankName, AccountNumber accountNumber, PersonName accountHolder,
			String memo, long firstMonthlyRent, long firstMaintenanceFee) {

		@Override
		public String toString() {
			return "LeaseEdit[tenantName=" + tenantName + ", tenantPhone=" + tenantPhone + "]";
		}

	}

	/** 수정 화면에 채울 현재 값과 안내 */
	public record EditView(long leaseId, String unitName, LeaseEdit current, LocalDate startDate,
			int otherLeasesOfTenant) {

		@Override
		public String toString() {
			return "EditView[leaseId=" + leaseId + "]";
		}

	}

	/** 월세 변경 화면: 고를 수 있는 달 */
	public record RentChangeView(long leaseId, String unitName, String tenantName, List<YearMonth> months) {
	}

	// ---------------------------------------------------------------- 계약 수정

	/**
	 * @throws LeaseEndedException
	 *             종료한 계약
	 */
	public EditView editView(long landlordId, long leaseId) {
		Lease lease = activeLease(landlordId, leaseId);
		Tenant tenant = tenant(landlordId, lease);
		LeaseRentHistory first = schedule(landlordId, lease).first();
		LeaseEdit current = new LeaseEdit(tenant.getName(), tenant.getPhone(), tenant.getMemo(), lease.getDeposit(),
				lease.getDueDay(), lease.getEndDate(), lease.getBankName(), lease.getAccountNumber(),
				lease.getAccountHolder(), lease.getMemo(), first.getMonthlyRent(), first.getMaintenanceFee());
		return new EditView(leaseId, propertyService.unit(landlordId, lease.getUnitId()).displayName(), current,
				lease.getStartDate(), otherLeasesOfTenant(landlordId, lease));
	}

	/**
	 * 계약을 고친다. 세입자 정보는 이 세입자의 다른 계약에도 함께 바뀐다(같은 사람). 나가는 날을 바꾸면 겹침을 다시 확인한다. 첫 달
	 * 금액도 여기서만 정정한다(M2 결정 4).
	 *
	 * @throws LeaseEndedException
	 *             종료한 계약
	 * @throws LeaseOverlapException
	 *             새 나가는 날이 같은 호실의 다른 진행 중 계약과 겹칠 때
	 * @throws ChangeAfterEndException
	 *             나가는 날을 당겼는데 그 뒤로 잡힌 월세 변경이 있을 때
	 * @throws DuplicateTenantPhoneException
	 *             바꾼 번호를 다른 세입자가 쓸 때
	 */
	@Transactional
	public void edit(long landlordId, long leaseId, LeaseEdit edit) {
		Lease lease = activeLease(landlordId, leaseId);
		// 같은 호실의 등록·수정을 차례로 처리한다 (겹침 확인과 EXCLUDE 교착 방지, ADR-019)
		propertyService.lockUnit(landlordId, lease.getUnitId());
		LeaseTerms terms = new LeaseTerms(edit.deposit(), edit.dueDay(), lease.getStartDate(), edit.endDate(),
				edit.bankName(), edit.accountNumber(), edit.accountHolder(), edit.memo());
		if (leases.existsActiveOverlap(landlordId, lease.getUnitId(), lease.getStartDate(), edit.endDate(),
				lease.getId())) {
			throw new LeaseOverlapException();
		}
		RentSchedule schedule = schedule(landlordId, lease);
		if (schedule.hasChangeAfter(YearMonth.from(edit.endDate().minusDays(1)))) {
			throw new ChangeAfterEndException();
		}

		Tenant tenant = tenant(landlordId, lease);
		String hash = hasher.hash(LeaseService.PHONE_PURPOSE, edit.tenantPhone().digits());
		tenants.findByLandlordIdAndPhoneHash(landlordId, hash).filter(other -> !other.getId().equals(tenant.getId()))
				.ifPresent(other -> {
					throw new DuplicateTenantPhoneException();
				});
		tenant.change(edit.tenantName(), edit.tenantPhone(), hash, edit.tenantMemo());
		lease.changeTerms(terms);
		schedule.correctFirst(edit.firstMonthlyRent(), edit.firstMaintenanceFee());
		try {
			leases.flush();
		} catch (DataIntegrityViolationException e) {
			if (Constraints.isViolated(e, LeaseService.OVERLAP_CONSTRAINT)) {
				throw new LeaseOverlapException(e);
			}
			if (Constraints.isViolated(e, LeaseService.TENANT_PHONE_UNIQUE)) {
				throw new DuplicateTenantPhoneException();
			}
			throw e;
		}
	}

	// ---------------------------------------------------------------- 월세 변경

	public RentChangeView rentChangeView(long landlordId, long leaseId) {
		Lease lease = activeLease(landlordId, leaseId);
		RentSchedule schedule = schedule(landlordId, lease);
		List<YearMonth> months = new ArrayList<>();
		schedule.earliestChangeMonth().ifPresent(earliest -> {
			for (YearMonth m = earliest; !m.isAfter(schedule.latestChangeMonth()); m = m.plusMonths(1)) {
				months.add(m);
			}
		});
		return new RentChangeView(leaseId, propertyService.unit(landlordId, lease.getUnitId()).displayName(),
				tenant(landlordId, lease).getName().value(), months);
	}

	/**
	 * 적용월부터 월세·관리비를 바꾼다. 같은 달에 아직 시작하지 않은 변경이 있으면 바꾼다(교체).
	 *
	 * @throws RentSchedule.RentChangeException
	 *             규칙 위반 (지난 달, 첫 달, 계약 뒤, 이미 시작된 달, 종료한 계약)
	 */
	@Transactional
	public void changeRent(long landlordId, long leaseId, YearMonth month, long monthlyRent, long maintenanceFee,
			String reason) {
		Lease lease = ownedLease(landlordId, leaseId);
		RentSchedule schedule = schedule(landlordId, lease);
		if (schedule.change(month, monthlyRent, maintenanceFee, reason).isEmpty()) {
			histories.saveAndFlush(schedule.newChange(month, monthlyRent, maintenanceFee, reason));
		} else {
			histories.flush();
		}
	}

	/** 취소 확인 화면에 보여 줄 변경 (취소할 수 없으면 RentChangeException) */
	public RentRow cancellableChange(long landlordId, long leaseId, long historyId) {
		Lease lease = ownedLease(landlordId, leaseId);
		LeaseRentHistory row = schedule(landlordId, lease).cancellable(historyId);
		return new RentRow(row.getId(), row.effectiveMonth(), row.getMonthlyRent(), row.getMaintenanceFee(),
				row.getReason(), true);
	}

	/** 아직 시작하지 않은 미래 변경을 취소한다 */
	@Transactional
	public void cancelChange(long landlordId, long leaseId, long historyId) {
		Lease lease = ownedLease(landlordId, leaseId);
		histories.delete(schedule(landlordId, lease).cancellable(historyId));
		histories.flush();
	}

	// ---------------------------------------------------------------- 종료·삭제

	/**
	 * 계약을 종료한다 (LEASE-3). 이후 청구를 만들지 않고(M3), 호실 상태에서 빠진다.
	 *
	 * @throws com.rentkeeper.lease.domain.InvalidLeaseValueException
	 *             끝난 날이 들어오는 날 전이거나 오늘(서울)보다 뒤일 때
	 */
	@Transactional
	public void end(long landlordId, long leaseId, LocalDate endedOn) {
		Lease lease = activeLease(landlordId, leaseId);
		lease.end(endedOn, today());
		leases.flush();
	}

	/**
	 * 잘못 등록한 계약을 지운다 (M2 결정 11: 청구가 없을 때만. M2에는 청구가 없어 항상 가능하다. M3에서 청구 유무 검사를
	 * 더한다). 월세 이력은 함께 지워진다(FK CASCADE). 세입자 정보는 남는다(보관 기간 미정, M2 결정 19).
	 *
	 * @return 계약이 있던 호실 ID
	 */
	@Transactional
	public long delete(long landlordId, long leaseId) {
		Lease lease = ownedLease(landlordId, leaseId);
		long unitId = lease.getUnitId();
		leases.delete(lease);
		leases.flush();
		return unitId;
	}

	public LocalDate today() {
		return LocalDate.now(clock);
	}

	// ---------------------------------------------------------------- 내부

	private Lease ownedLease(long landlordId, long leaseId) {
		return leases.findByIdAndLandlordId(leaseId, landlordId)
				.orElseThrow(() -> new ResourceNotFoundException("lease"));
	}

	private Lease activeLease(long landlordId, long leaseId) {
		Lease lease = ownedLease(landlordId, leaseId);
		if (!lease.isActive()) {
			throw new LeaseEndedException();
		}
		return lease;
	}

	private Tenant tenant(long landlordId, Lease lease) {
		return tenants.findByIdAndLandlordId(lease.getTenantId(), landlordId)
				.orElseThrow(() -> new ResourceNotFoundException("tenant"));
	}

	private RentSchedule schedule(long landlordId, Lease lease) {
		return new RentSchedule(lease,
				histories.findByLeaseIdAndLandlordIdOrderByEffectiveFromAsc(lease.getId(), landlordId),
				YearMonth.now(clock));
	}

	private int otherLeasesOfTenant(long landlordId, Lease lease) {
		return (int) leases.findByTenantIdAndLandlordId(lease.getTenantId(), landlordId).stream()
				.filter(other -> !other.getId().equals(lease.getId())).count();
	}

}
