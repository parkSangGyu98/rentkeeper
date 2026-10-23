package com.rentkeeper.billing.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.rentkeeper.billing.application.ChargeExceptions.MonthUnavailableException;
import com.rentkeeper.billing.application.ChargeExceptions.MonthUnavailableException.Reason;
import com.rentkeeper.billing.application.ChargeExceptions.ZeroTotalException;
import com.rentkeeper.billing.application.ChargePlanner.PlannedCharge;
import com.rentkeeper.billing.domain.ChangeActor;
import com.rentkeeper.billing.domain.Charge;
import com.rentkeeper.billing.domain.ChargeCalendar;
import com.rentkeeper.billing.domain.ChargeItem;
import com.rentkeeper.billing.domain.ChargeRepository;
import com.rentkeeper.billing.domain.ChargeSource;
import com.rentkeeper.billing.domain.ChargeStatus;
import com.rentkeeper.common.error.ResourceNotFoundException;
import com.rentkeeper.common.jpa.Constraints;
import com.rentkeeper.lease.application.LeaseBillingService;
import com.rentkeeper.lease.application.LeaseBillingTerms;
import com.rentkeeper.lease.application.LeaseService;
import com.rentkeeper.lease.application.LeaseViews.LeaseSummary;

/**
 * 받을 월세 직접 만들기 (CHG-4, M3-b 결정 7·8·9). 계약 등록 전에 납부일이 지난 달처럼 자동으로 만들어지지 않는 달을
 * 임대인이 만든다. 만들자마자 발행한다(배치와 같은 ChargeIssuer). 계약 행을 잠가 배치·계약 수정과 겹치지 않게 하고, 같은
 * 달은 UNIQUE(uk_charge_lease_month)가 한 번 더 막는다. 남의 계약이면 404.
 */
@Service
@Transactional
public class ManualChargeService {

	private final LeaseBillingService leaseBilling;

	private final LeaseService leaseService;

	private final ChargeRepository charges;

	private final ChargeIssuer issuer;

	private final Clock clock;

	ManualChargeService(LeaseBillingService leaseBilling, LeaseService leaseService, ChargeRepository charges,
			ChargeIssuer issuer, Clock clock) {
		this.leaseBilling = leaseBilling;
		this.leaseService = leaseService;
		this.charges = charges;
		this.issuer = issuer;
		this.clock = clock;
	}

	/**
	 * 만들기 화면.
	 *
	 * @param months
	 *            만들 수 있는 달 (받을 월세가 없는 달, 오래된 순)
	 * @param month
	 *            고른 달 (없으면 null: 달 고르기 화면)
	 * @param dueDate
	 *            고른 달의 납부일
	 */
	public record NewChargeView(long leaseId, String unitName, String tenantName, List<YearMonth> months,
			YearMonth month, LocalDate dueDate, long monthlyRent, long maintenanceFee) {

		@Override
		public String toString() {
			return "NewChargeView[leaseId=" + leaseId + ", month=" + month + "]";
		}

	}

	/**
	 * @param month
	 *            고른 달 (null이면 달만 고른다)
	 * @throws MonthUnavailableException
	 *             고른 달을 만들 수 없을 때
	 */
	@Transactional(readOnly = true)
	public NewChargeView view(long landlordId, long leaseId, YearMonth month) {
		LeaseBillingTerms terms = leaseBilling.billingTerms(leaseId, landlordId)
				.orElseThrow(() -> new ResourceNotFoundException("lease"));
		LeaseSummary lease = leaseService.summaries(landlordId, Set.of(leaseId)).get(leaseId);
		List<YearMonth> months = creatableMonths(terms, existingMonths(landlordId, leaseId));
		if (month == null) {
			return new NewChargeView(leaseId, lease.unitName(), lease.tenantName(), months, null, null, 0, 0);
		}
		requireCreatable(landlordId, terms, month, months);
		LeaseBillingTerms.Rent rent = terms.rentFor(month).orElse(new LeaseBillingTerms.Rent(month, 0, 0));
		return new NewChargeView(leaseId, lease.unitName(), lease.tenantName(), months, month,
				ChargeCalendar.dueDate(month, terms.dueDay()), rent.monthlyRent(), rent.maintenanceFee());
	}

	/**
	 * 그 달 받을 월세를 만들고 발행한다. 금액은 화면에서 고친 값(M3-b 결정 8).
	 *
	 * @return 만든 받을 월세 ID
	 * @throws MonthUnavailableException
	 *             이미 있거나(취소 포함) 기간 밖인 달. 동시에 배치가 먼저 만든 경우도 포함
	 * @throws ZeroTotalException
	 *             월세 + 관리비가 0원
	 */
	public long create(long landlordId, long leaseId, YearMonth month, long monthlyRent, long maintenanceFee) {
		LeaseBillingTerms terms = leaseBilling.lockForBilling(leaseId, landlordId)
				.orElseThrow(() -> new ResourceNotFoundException("lease"));
		requireCreatable(landlordId, terms, month, creatableMonths(terms, existingMonths(landlordId, leaseId)));
		ChargeItem.requireAmount(monthlyRent);
		ChargeItem.requireAmount(maintenanceFee);
		if (monthlyRent + maintenanceFee == 0) {
			throw new ZeroTotalException();
		}
		PlannedCharge planned = new PlannedCharge(month, ChargeCalendar.dueDate(month, terms.dueDay()), monthlyRent,
				maintenanceFee);
		try {
			Charge charge = issuer.createAndIssue(landlordId, leaseId, planned, ChargeSource.MANUAL,
					ChangeActor.landlord(landlordId), LocalDate.now(clock), clock.instant());
			charges.flush();
			return charge.getId();
		} catch (DataIntegrityViolationException e) {
			if (Constraints.isViolated(e, "uk_charge_lease_month")) {
				throw new MonthUnavailableException(Reason.EXISTS);
			}
			throw e;
		}
	}

	/** 들어오는 달 ~ min(마지막 청구월, 다음 달) 중 받을 월세가 없는 달 (M3-b 결정 7) */
	List<YearMonth> creatableMonths(LeaseBillingTerms terms, Set<YearMonth> existing) {
		Optional<YearMonth> last = terms.lastMonth();
		if (last.isEmpty()) {
			return List.of();
		}
		YearMonth nextMonth = YearMonth.now(clock).plusMonths(1);
		YearMonth end = last.get().isBefore(nextMonth) ? last.get() : nextMonth;
		List<YearMonth> months = new ArrayList<>();
		for (YearMonth m = terms.firstMonth(); !m.isAfter(end); m = m.plusMonths(1)) {
			if (!existing.contains(m)) {
				months.add(m);
			}
		}
		return months;
	}

	private void requireCreatable(long landlordId, LeaseBillingTerms terms, YearMonth month,
			List<YearMonth> creatable) {
		if (creatable.contains(month)) {
			return;
		}
		Optional<Charge> existing = charges.findByLeaseIdAndLandlordIdAndBillingMonth(terms.leaseId(), landlordId,
				month.atDay(1));
		if (existing.isPresent()) {
			throw new MonthUnavailableException(
					existing.get().getStatus() == ChargeStatus.CANCELED ? Reason.CANCELED : Reason.EXISTS);
		}
		throw new MonthUnavailableException(Reason.OUT_OF_RANGE);
	}

	private Set<YearMonth> existingMonths(long landlordId, long leaseId) {
		return charges.billingMonths(leaseId, landlordId).stream().map(YearMonth::from).collect(Collectors.toSet());
	}

}
