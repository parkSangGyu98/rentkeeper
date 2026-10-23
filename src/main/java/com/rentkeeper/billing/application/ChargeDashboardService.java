package com.rentkeeper.billing.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.rentkeeper.billing.application.ChargeDashboardViews.ChargeDashboard;
import com.rentkeeper.billing.application.ChargeDashboardViews.DashboardRow;
import com.rentkeeper.billing.application.ChargeDashboardViews.Summary;
import com.rentkeeper.billing.domain.Charge;
import com.rentkeeper.billing.domain.ChargeRepository;
import com.rentkeeper.billing.domain.ChargeRepository.Totals;
import com.rentkeeper.billing.domain.ChargeStatus;
import com.rentkeeper.billing.domain.ChargeStatusHistoryRepository;
import com.rentkeeper.billing.domain.ChargeStatusHistoryRepository.ChargeCount;
import com.rentkeeper.lease.application.LeaseService;
import com.rentkeeper.lease.application.LeaseViews.LeaseSummary;

/**
 * 대시보드의 받을 월세 부분 (DASH-1·2, M4 결정 14). 합계·건수는 DB가 세고, 목록은 앞의 limit건만 읽어 그만큼만
 * 이름을 복호화한다(PRD 8.3: 대시보드 p95 500ms).
 */
@Service
@Transactional(readOnly = true)
public class ChargeDashboardService {

	private final ChargeRepository charges;

	private final ChargeStatusHistoryRepository statusHistories;

	private final LeaseService leaseService;

	private final Clock clock;

	ChargeDashboardService(ChargeRepository charges, ChargeStatusHistoryRepository statusHistories,
			LeaseService leaseService, Clock clock) {
		this.charges = charges;
		this.statusHistories = statusHistories;
		this.leaseService = leaseService;
		this.clock = clock;
	}

	/**
	 * @param leaseIds
	 *            이 계약들만 (건물 고르기). null이면 전체
	 * @param limit
	 *            목록마다 보일 건수
	 */
	public ChargeDashboard dashboard(long landlordId, Collection<Long> leaseIds, int limit) {
		LocalDate today = LocalDate.now(clock);
		YearMonth month = YearMonth.from(today);
		if (leaseIds != null && leaseIds.isEmpty()) {
			return new ChargeDashboard(month, Summary.EMPTY, Summary.EMPTY, List.of(), Summary.EMPTY, List.of());
		}
		boolean all = leaseIds == null;
		Summary thisMonth = summary(all
				? charges.monthTotals(landlordId, month.atDay(1))
				: charges.monthTotalsOfLeases(landlordId, month.atDay(1), leaseIds));
		Summary reported = summary(all
				? charges.statusTotals(landlordId, ChargeStatus.TENANT_REPORTED)
				: charges.statusTotalsOfLeases(landlordId, ChargeStatus.TENANT_REPORTED, leaseIds));
		Summary overdue = summary(all
				? charges.statusTotals(landlordId, ChargeStatus.OVERDUE)
				: charges.statusTotalsOfLeases(landlordId, ChargeStatus.OVERDUE, leaseIds));
		List<Charge> reportedCharges = all
				? charges.findByLandlordIdAndStatusOrderByTenantReportedAtAscIdAsc(landlordId,
						ChargeStatus.TENANT_REPORTED, Limit.of(limit))
				: charges.findByLandlordIdAndStatusAndLeaseIdInOrderByTenantReportedAtAscIdAsc(landlordId,
						ChargeStatus.TENANT_REPORTED, leaseIds, Limit.of(limit));
		List<Charge> overdueCharges = all
				? charges.findByLandlordIdAndStatusOrderByDueDateAscIdAsc(landlordId, ChargeStatus.OVERDUE,
						Limit.of(limit))
				: charges.findByLandlordIdAndStatusAndLeaseIdInOrderByDueDateAscIdAsc(landlordId, ChargeStatus.OVERDUE,
						leaseIds, Limit.of(limit));

		Map<Long, LeaseSummary> names = leaseService.summaries(landlordId,
				Stream.concat(reportedCharges.stream(), overdueCharges.stream()).map(Charge::getLeaseId)
						.collect(Collectors.toSet()));
		Map<Long, Long> reportCounts = reportedCharges.isEmpty()
				? Map.of()
				: statusHistories.reportCounts(landlordId, reportedCharges.stream().map(Charge::getId).toList())
						.stream().collect(Collectors.toMap(ChargeCount::getChargeId, ChargeCount::getCount));
		List<DashboardRow> reportedRows = reportedCharges.stream()
				.map(c -> row(c, names.get(c.getLeaseId()), reportCounts.getOrDefault(c.getId(), 0L),
						LocalDate.ofInstant(c.getTenantReportedAt(), clock.getZone()), 0))
				.toList();
		List<DashboardRow> overdueRows = overdueCharges.stream()
				.map(c -> row(c, names.get(c.getLeaseId()), 0, null, ChronoUnit.DAYS.between(c.getDueDate(), today)))
				.toList();
		return new ChargeDashboard(month, thisMonth, reported, reportedRows, overdue, overdueRows);
	}

	private static DashboardRow row(Charge c, LeaseSummary lease, long reportCount, LocalDate reportedOn,
			long daysOverdue) {
		return new DashboardRow(c.getId(), lease.unitName(), lease.tenantName(), c.getBillingMonth(), c.getDueDate(),
				c.getTotalAmount(), c.getPaidAmount(), reportCount, reportedOn, daysOverdue);
	}

	private static Summary summary(Totals totals) {
		return new Summary(totals.getCount(), totals.getTotal(), totals.getPaid(), totals.getBalance());
	}

}
