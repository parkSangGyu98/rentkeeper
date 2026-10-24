package com.rentkeeper.billing.application;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import com.rentkeeper.common.money.Won;

/** 대시보드의 받을 월세 부분 (DASH-1·2, M4 결정 14). 화면 문자열은 여기서 만든다 */
public final class ChargeDashboardViews {

	private ChargeDashboardViews() {
	}

	/**
	 * @param thisMonth
	 *            이번 달 받을 월세 (취소 제외)
	 * @param reported
	 *            확인 기다리는 세입자 신고 (모든 달)
	 * @param overdue
	 *            납부일 지남 (모든 달, 오래 지난 것부터)
	 */
	public record ChargeDashboard(YearMonth month, Summary thisMonth, Summary reported, List<DashboardRow> reportedRows,
			Summary overdue, List<DashboardRow> overdueRows) {

		public String monthText() {
			return month.getYear() + "년 " + month.getMonthValue() + "월";
		}

	}

	/**
	 * 건수와 금액 (원).
	 *
	 * @param balance
	 *            남은 돈 (청구마다 0 아래로 내려가지 않게 더한 값)
	 */
	public record Summary(long count, long total, long paid, long balance) {

		static final Summary EMPTY = new Summary(0, 0, 0, 0);

		public String totalText() {
			return Won.format(total);
		}

		public String paidText() {
			return Won.format(paid);
		}

		public String balanceText() {
			return Won.format(balance);
		}

	}

	/**
	 * 대시보드 목록 한 줄.
	 *
	 * @param reportCount
	 *            세입자 신고 횟수 (확인 기다림 목록에서만)
	 * @param reportedOn
	 *            세입자가 알린 날 (서울, 확인 기다림 목록에서만)
	 * @param daysOverdue
	 *            납부일에서 지난 날 수 (납부일 지남 목록에서만)
	 */
	public record DashboardRow(long chargeId, String unitName, String tenantName, YearMonth month, LocalDate dueDate,
			long totalAmount, long paidAmount, long reportCount, LocalDate reportedOn, long daysOverdue) {

		public String monthText() {
			return month.getYear() + "년 " + month.getMonthValue() + "월";
		}

		public String balanceText() {
			return Won.format(Math.max(0, totalAmount - paidAmount));
		}

		public String reportedOnText() {
			return reportedOn == null ? "" : reportedOn.getMonthValue() + "월 " + reportedOn.getDayOfMonth() + "일";
		}

		@Override
		public String toString() {
			return "DashboardRow[chargeId=" + chargeId + "]";
		}

	}

}
