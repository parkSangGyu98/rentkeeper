package com.rentkeeper.dashboard.application;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.rentkeeper.billing.application.ChargeDashboardService;
import com.rentkeeper.billing.application.ChargeDashboardViews.ChargeDashboard;
import com.rentkeeper.lease.application.LeaseService;
import com.rentkeeper.lease.application.LeaseViews.EndingLeases;
import com.rentkeeper.property.application.PropertyService;
import com.rentkeeper.property.application.PropertyViews.PropertyDetail;
import com.rentkeeper.property.application.PropertyViews.PropertyOption;
import com.rentkeeper.property.application.PropertyViews.PropertySummary;
import com.rentkeeper.property.application.PropertyViews.UnitRow;
import com.rentkeeper.property.domain.UnitStatus;

/**
 * 임대인 대시보드 (DASH-1~4, M4 결정 14): 확인 기다리는 입금 신고, 이번 달 받을 돈, 납부일 지남, 곧 끝나는 계약,
 * 공실. 건물을 고르면 그 건물 호실의 계약만 센다. 남의 건물이면 404(PropertyService).
 */
@Service
@Transactional(readOnly = true)
public class DashboardService {

	/** 목록마다 보일 건수 (나머지는 건수로만, "모두 보기" 링크) */
	public static final int LIST_LIMIT = 10;

	/** "곧 끝나는 계약"의 기준 (PRD NOTI-5 D-90과 같게) */
	public static final int ENDING_WITHIN_DAYS = 90;

	private final PropertyService propertyService;

	private final LeaseService leaseService;

	private final ChargeDashboardService chargeDashboardService;

	DashboardService(PropertyService propertyService, LeaseService leaseService,
			ChargeDashboardService chargeDashboardService) {
		this.propertyService = propertyService;
		this.leaseService = leaseService;
		this.chargeDashboardService = chargeDashboardService;
	}

	/**
	 * 대시보드.
	 *
	 * @param selected
	 *            고른 건물 (null이면 모든 건물). 남의 건물이면 ResourceNotFoundException(404)
	 * @param properties
	 *            건물 고르기 칸에 보일 건물들
	 * @param unitCount
	 *            호실 수 (고른 건물 기준)
	 * @param vacantCount
	 *            오늘 공실인 호실 수 (DASH-4)
	 */
	public record DashboardView(PropertyOption selected, List<PropertyOption> properties, ChargeDashboard charges,
			EndingLeases ending, int unitCount, int vacantCount) {

		public boolean empty() {
			return properties.isEmpty();
		}

		public Long selectedId() {
			return selected == null ? null : selected.id();
		}

	}

	public DashboardView view(long landlordId, Long propertyId) {
		if (propertyId == null) {
			// 모든 건물: 공실 수에 모든 호실의 상태가 필요하다
			List<PropertySummary> summaries = propertyService.list(landlordId);
			List<PropertyOption> options = summaries.stream().map(p -> new PropertyOption(p.id(), p.name())).toList();
			return new DashboardView(null, options, chargeDashboardService.dashboard(landlordId, null, LIST_LIMIT),
					leaseService.endingSoon(landlordId, null, ENDING_WITHIN_DAYS, LIST_LIMIT),
					summaries.stream().mapToInt(PropertySummary::unitCount).sum(),
					summaries.stream().mapToInt(PropertySummary::vacantCount).sum());
		}
		// 남의 건물이면 여기서 404. 호실 상태는 그 건물 것만 계산한다 (고르기 칸은 이름만)
		PropertyDetail detail = propertyService.detail(landlordId, propertyId);
		List<PropertyOption> options = propertyService.options(landlordId);
		List<Long> unitIds = detail.units().stream().map(UnitRow::id).toList();
		int vacant = (int) detail.units().stream().filter(u -> u.status() == UnitStatus.VACANT).count();
		return new DashboardView(new PropertyOption(detail.id(), detail.name()), options,
				chargeDashboardService.dashboard(landlordId, leaseService.leaseIdsOfUnits(landlordId, unitIds),
						LIST_LIMIT),
				leaseService.endingSoon(landlordId, unitIds, ENDING_WITHIN_DAYS, LIST_LIMIT), unitIds.size(), vacant);
	}

}
