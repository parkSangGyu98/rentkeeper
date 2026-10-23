package com.rentkeeper.billing.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.rentkeeper.billing.domain.ChangeActor;
import com.rentkeeper.billing.domain.Charge;
import com.rentkeeper.billing.domain.ChargeRepository;
import com.rentkeeper.billing.domain.ChargeStatus;
import com.rentkeeper.billing.domain.ChargeStatusHistory;
import com.rentkeeper.billing.domain.ChargeStatusHistoryRepository;
import com.rentkeeper.lease.application.ChargedMonths;

/** lease 모듈이 정한 {@link ChargedMonths}의 구현 (M3 필수 확인, M3 결정 5) */
@Component
class LeaseChargedMonths implements ChargedMonths {

	private final ChargeRepository charges;

	private final ChargeStatusHistoryRepository statusHistories;

	private final Clock clock;

	LeaseChargedMonths(ChargeRepository charges, ChargeStatusHistoryRepository statusHistories, Clock clock) {
		this.charges = charges;
		this.statusHistories = statusHistories;
		this.clock = clock;
	}

	@Override
	public boolean any(long landlordId, long leaseId) {
		return charges.existsByLeaseIdAndLandlordId(leaseId, landlordId);
	}

	@Override
	public boolean exists(long landlordId, long leaseId, YearMonth month) {
		return charges.existsByLeaseIdAndLandlordIdAndBillingMonth(leaseId, landlordId, month.atDay(1));
	}

	@Override
	public Set<YearMonth> months(long landlordId, long leaseId) {
		return charges.billingMonths(leaseId, landlordId).stream().map(YearMonth::from).collect(Collectors.toSet());
	}

	@Override
	public Optional<YearMonth> latest(long landlordId, long leaseId) {
		return charges.latestBillingMonth(leaseId, landlordId).map(YearMonth::from);
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public Cancellation cancelAfter(long landlordId, long leaseId, Optional<YearMonth> lastMonth, String reason) {
		// 마지막 청구월이 없으면(하루도 살지 않음) 모든 달
		LocalDate after = lastMonth.map(m -> m.atDay(1)).orElse(LocalDate.EPOCH);
		Instant now = clock.instant();
		ChangeActor actor = ChangeActor.landlord(landlordId);
		int canceled = 0;
		int kept = 0;
		for (Charge charge : charges.findByLeaseIdAndLandlordIdAndBillingMonthAfterOrderByBillingMonth(leaseId,
				landlordId, after)) {
			if (charge.getStatus() == ChargeStatus.CANCELED) {
				continue;
			}
			// 입금 기록이 있는(또는 다 받은) 청구는 임대인이 직접 확인한다
			if (charge.getPaidAmount() > 0 || charge.getStatus() == ChargeStatus.PAID) {
				kept++;
				continue;
			}
			statusHistories.save(ChargeStatusHistory.of(charge, charge.cancel(reason, now), actor, reason, now));
			canceled++;
		}
		charges.flush();
		return new Cancellation(canceled, kept);
	}

}
