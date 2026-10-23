package com.rentkeeper.billing.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.rentkeeper.billing.application.ChargeViews.ChargeDetail;
import com.rentkeeper.billing.application.ChargeViews.ChargeRow;
import com.rentkeeper.billing.application.ChargeViews.FilterTab;
import com.rentkeeper.billing.application.ChargeViews.ItemHistoryRow;
import com.rentkeeper.billing.application.ChargeViews.ItemRow;
import com.rentkeeper.billing.application.ChargeViews.MonthView;
import com.rentkeeper.billing.application.ChargeViews.PaymentRow;
import com.rentkeeper.billing.application.ChargeViews.StatusFilter;
import com.rentkeeper.billing.application.ChargeViews.StatusHistoryRow;
import com.rentkeeper.billing.domain.Charge;
import com.rentkeeper.billing.domain.ChargeItemHistoryRepository;
import com.rentkeeper.billing.domain.ChargeItemRepository;
import com.rentkeeper.billing.domain.ChargeRepository;
import com.rentkeeper.billing.domain.ChargeStatus;
import com.rentkeeper.billing.domain.ChargeStatusHistoryRepository;
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

	private final ChargeStatusHistoryRepository statusHistories;

	private final ChargeItemHistoryRepository itemHistories;

	private final LeaseService leaseService;

	private final Clock clock;

	ChargeQueryService(ChargeRepository charges, ChargeItemRepository items, PaymentRecordRepository payments,
			ChargeStatusHistoryRepository statusHistories, ChargeItemHistoryRepository itemHistories,
			LeaseService leaseService, Clock clock) {
		this.charges = charges;
		this.items = items;
		this.payments = payments;
		this.statusHistories = statusHistories;
		this.itemHistories = itemHistories;
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

	/**
	 * 한 달 받을 월세 목록 (납부일 순, 취소한 것도 보인다). 고른 상태의 줄만 보이고, 합계와 상태별 건수는 고른 상태와 관계없이 달
	 * 전체다 (M3-b 결정 12).
	 */
	public MonthView month(long landlordId, YearMonth month, StatusFilter filter) {
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
		List<ChargeRow> active = rows.stream().filter(r -> !r.canceled()).toList();
		long total = active.stream().mapToLong(ChargeRow::totalAmount).sum();
		long paid = active.stream().mapToLong(ChargeRow::paidAmount).sum();
		long balance = active.stream().mapToLong(ChargeRow::balance).sum();
		List<FilterTab> tabs = Arrays.stream(StatusFilter.values()).map(f -> new FilterTab(f,
				rows.stream().filter(r -> f.matches(r.status())).count(), f == filter, ChargeViews.listHref(month, f)))
				.toList();
		List<ChargeRow> shown = rows.stream().filter(r -> filter.matches(r.status())).toList();
		return new MonthView(month, month.equals(thisMonth()), filter, tabs, shown, total, paid, balance,
				rows.isEmpty());
	}

	/** 받을 월세 상세 (남의 청구면 404) */
	public ChargeDetail detail(long landlordId, long chargeId) {
		Charge charge = charges.findByIdAndLandlordId(chargeId, landlordId)
				.orElseThrow(() -> new ResourceNotFoundException("charge"));
		LeaseSummary lease = leaseService.summaries(landlordId, Set.of(charge.getLeaseId())).get(charge.getLeaseId());
		List<ItemRow> itemRows = items.findByChargeIdAndLandlordIdOrderByIdAsc(chargeId, landlordId).stream()
				.map(i -> new ItemRow(i.getId(), i.getType(), i.getLabel(), i.getAmount(), i.getMemo())).toList();
		List<PaymentRow> paymentRows = payments.findByChargeIdAndLandlordIdOrderByIdAsc(chargeId, landlordId).stream()
				.map(p -> new PaymentRow(p.getId(), p.getPaidOn(), p.getAmount(), p.getMemo(), p.isDeleted(),
						p.getDeleteReason(), p.getReplacesId()))
				.toList();
		// 이력 시각은 UTC로 저장한다. 화면에는 주입한 Clock의 시간대(서울)로 보인다
		ZoneId zone = clock.getZone();
		List<StatusHistoryRow> statusRows = statusHistories
				.findByChargeIdAndLandlordIdOrderByIdAsc(chargeId, landlordId).stream()
				.map(h -> new StatusHistoryRow(LocalDateTime.ofInstant(h.getCreatedAt(), zone), h.getFromStatus(),
						h.getToStatus(), h.getActorType(), h.getReason()))
				.toList();
		List<ItemHistoryRow> itemHistoryRows = itemHistories
				.findByChargeIdAndLandlordIdOrderByIdAsc(chargeId, landlordId).stream()
				.map(h -> new ItemHistoryRow(LocalDateTime.ofInstant(h.getCreatedAt(), zone), h.getAction(),
						h.getLabel(), h.getBeforeAmount(), h.getAfterAmount(), h.getActorType()))
				.toList();
		return new ChargeDetail(charge.getId(), charge.getVersion(), charge.getLeaseId(), lease.unitName(),
				lease.tenantName(), charge.getBillingMonth(), charge.getDueDate(), charge.getStatus(),
				charge.getTotalAmount(), charge.getPaidAmount(), charge.getCancelReason(), itemRows, paymentRows,
				YearMonth.from(lease.startDate()).atDay(1), statusRows, itemHistoryRows);
	}

	/** 지금 상태 (충돌 안내 문구를 고를 때) */
	public ChargeStatus status(long landlordId, long chargeId) {
		return charges.findByIdAndLandlordId(chargeId, landlordId).map(Charge::getStatus)
				.orElseThrow(() -> new ResourceNotFoundException("charge"));
	}

}
