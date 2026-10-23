package com.rentkeeper.billing.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
import com.rentkeeper.billing.application.TenantChargeViews.TenantCharge;
import com.rentkeeper.billing.application.TenantChargeViews.TenantItem;
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

	/** 세입자 화면이 보는 지난 달 수 (M4 결정 6) */
	public static final int TENANT_MONTHS = 12;

	/**
	 * 세입자가 지금 볼 받을 월세 (M4 결정 6): 이번 달 것(다 확인됐어도), 그리고 아직 다 확인되지 않은 지난
	 * {@value #TENANT_MONTHS}개 달·다음 달 것. 취소한 것은 보이지 않는다. 범위는 링크가 가리키는 계약 하나다.
	 */
	public List<TenantCharge> tenantDue(long landlordId, long leaseId) {
		YearMonth thisMonth = thisMonth();
		return tenantCharges(landlordId, leaseId, thisMonth.minusMonths(TENANT_MONTHS), thisMonth.plusMonths(1))
				.stream().filter(
						c -> c.getBillingMonth().equals(thisMonth) || c.getStatus() != ChargeStatus.PAID)
				.map(c -> tenantCharge(c, items.findByChargeIdAndLandlordIdOrderByIdAsc(c.getId(), landlordId).stream()
						.map(i -> new TenantItem(i.getLabel(), i.getAmount())).toList()))
				.toList();
	}

	/**
	 * 세입자의 지난 기록: 최근 {@value #TENANT_MONTHS}개월(다음 달 포함), 최근 달이 위. 취소한 것은 보이지 않는다
	 */
	public List<TenantCharge> tenantHistory(long landlordId, long leaseId) {
		YearMonth thisMonth = thisMonth();
		List<TenantCharge> rows = new ArrayList<>(
				tenantCharges(landlordId, leaseId, thisMonth.minusMonths(TENANT_MONTHS - 1), thisMonth.plusMonths(1))
						.stream().map(c -> tenantCharge(c, List.of())).toList());
		Collections.reverse(rows);
		return rows;
	}

	private List<Charge> tenantCharges(long landlordId, long leaseId, YearMonth from, YearMonth to) {
		return charges
				.findByLeaseIdAndLandlordIdAndBillingMonthBetweenOrderByBillingMonthAsc(leaseId, landlordId,
						from.atDay(1), to.atDay(1))
				.stream().filter(c -> c.getStatus() != ChargeStatus.CANCELED).toList();
	}

	private static TenantCharge tenantCharge(Charge c, List<TenantItem> itemRows) {
		return new TenantCharge(c.getId(), c.getBillingMonth(), c.getDueDate(), c.getTotalAmount(), c.getPaidAmount(),
				c.getStatus(), itemRows);
	}

	/** 지금 상태 (충돌 안내 문구를 고를 때) */
	public ChargeStatus status(long landlordId, long chargeId) {
		return charges.findByIdAndLandlordId(chargeId, landlordId).map(Charge::getStatus)
				.orElseThrow(() -> new ResourceNotFoundException("charge"));
	}

}
