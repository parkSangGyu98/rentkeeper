package com.rentkeeper.billing.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.rentkeeper.billing.application.ChargeViews.ChargeDetail;
import com.rentkeeper.billing.application.ChargeViews.ChargeRow;
import com.rentkeeper.billing.application.ChargeViews.ItemRow;
import com.rentkeeper.billing.application.ChargeViews.MonthView;
import com.rentkeeper.billing.application.ChargeViews.PaymentRow;
import com.rentkeeper.billing.domain.Charge;
import com.rentkeeper.billing.domain.ChargeItemRepository;
import com.rentkeeper.billing.domain.ChargeRepository;
import com.rentkeeper.billing.domain.ChargeStatus;
import com.rentkeeper.billing.domain.PaymentRecordRepository;
import com.rentkeeper.common.error.ResourceNotFoundException;
import com.rentkeeper.lease.application.LeaseService;
import com.rentkeeper.lease.application.LeaseViews.LeaseSummary;

/** 받을 월세 조회 (읽기 전용). 모든 조회는 로그인한 임대인의 것만 (ADR-018) */
@Service
@Transactional(readOnly = true)
public class ChargeQueryService {

	private final ChargeRepository charges;

	private final ChargeItemRepository items;

	private final PaymentRecordRepository payments;

	private final LeaseService leaseService;

	private final Clock clock;

	ChargeQueryService(ChargeRepository charges, ChargeItemRepository items, PaymentRecordRepository payments,
			LeaseService leaseService, Clock clock) {
		this.charges = charges;
		this.items = items;
		this.payments = payments;
		this.leaseService = leaseService;
		this.clock = clock;
	}

	/** 오늘 (서울, 주입한 Clock 기준) */
	public LocalDate today() {
		return LocalDate.now(clock);
	}

	/** 이번 달 (서울, 주입한 Clock 기준) */
	public YearMonth thisMonth() {
		return YearMonth.now(clock);
	}

	/** 한 달 받을 월세 목록 (납부일 순, 취소한 것도 보인다) */
	public MonthView month(long landlordId, YearMonth month) {
		List<Charge> found = charges.findByLandlordIdAndBillingMonthOrderByDueDateAscIdAsc(landlordId, month.atDay(1));
		Map<Long, LeaseSummary> leases = leaseService.summaries(landlordId,
				found.stream().map(Charge::getLeaseId).collect(Collectors.toSet()));
		// 같은 호실에 이 달 청구가 둘 이상: 한 달 안에 세입자가 바뀐 경우 (M3 결정 3)
		Map<Long, Long> activePerUnit = found.stream().filter(c -> c.getStatus() != ChargeStatus.CANCELED)
				.collect(Collectors.groupingBy(c -> leases.get(c.getLeaseId()).unitId(), Collectors.counting()));
		Set<Long> sharedUnits = activePerUnit.entrySet().stream().filter(e -> e.getValue() > 1).map(Map.Entry::getKey)
				.collect(Collectors.toSet());
		List<ChargeRow> rows = found.stream().map(c -> {
			LeaseSummary lease = leases.get(c.getLeaseId());
			return new ChargeRow(c.getId(), lease.unitName(), lease.tenantName(), c.getDueDate(), c.getTotalAmount(),
					c.getPaidAmount(), c.getStatus(),
					c.getStatus() != ChargeStatus.CANCELED && sharedUnits.contains(lease.unitId()));
		}).toList();
		List<Charge> active = found.stream().filter(c -> c.getStatus() != ChargeStatus.CANCELED).toList();
		long total = active.stream().mapToLong(Charge::getTotalAmount).sum();
		long paid = active.stream().mapToLong(Charge::getPaidAmount).sum();
		return new MonthView(month, month.equals(thisMonth()), rows, total, paid);
	}

	/** 받을 월세 상세 (남의 청구면 404) */
	public ChargeDetail detail(long landlordId, long chargeId) {
		Charge charge = charges.findByIdAndLandlordId(chargeId, landlordId)
				.orElseThrow(() -> new ResourceNotFoundException("charge"));
		LeaseSummary lease = leaseService.summaries(landlordId, Set.of(charge.getLeaseId())).get(charge.getLeaseId());
		List<ItemRow> itemRows = items.findByChargeIdAndLandlordIdOrderByIdAsc(chargeId, landlordId).stream()
				.map(i -> new ItemRow(i.getId(), i.getType().getLabel(), i.getLabel(), i.getAmount(), i.getMemo()))
				.toList();
		List<PaymentRow> paymentRows = payments.findByChargeIdAndLandlordIdOrderByIdAsc(chargeId, landlordId).stream()
				.map(p -> new PaymentRow(p.getId(), p.getPaidOn(), p.getAmount(), p.getMemo(), p.isDeleted(),
						p.getDeleteReason(), p.getReplacesId()))
				.toList();
		return new ChargeDetail(charge.getId(), charge.getVersion(), charge.getLeaseId(), lease.unitName(),
				lease.tenantName(), charge.getBillingMonth(), charge.getDueDate(), charge.getStatus(),
				charge.getTotalAmount(), charge.getPaidAmount(), charge.getCancelReason(), itemRows, paymentRows,
				YearMonth.from(lease.startDate()).atDay(1));
	}

	/** 지금 상태 (충돌 안내 문구를 고를 때) */
	public ChargeStatus status(long landlordId, long chargeId) {
		return charges.findByIdAndLandlordId(chargeId, landlordId).map(Charge::getStatus)
				.orElseThrow(() -> new ResourceNotFoundException("charge"));
	}

}
