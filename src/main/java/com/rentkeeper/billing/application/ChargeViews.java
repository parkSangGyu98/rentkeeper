package com.rentkeeper.billing.application;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import com.rentkeeper.billing.domain.ChargeStatus;
import com.rentkeeper.common.money.Won;

/** 받을 월세 화면용 값 (화면 문자열은 여기서 만든다: Thymeleaf 3.1은 템플릿의 정적 호출을 막는다) */
public final class ChargeViews {

	private ChargeViews() {
	}

	/**
	 * 한 달 받을 월세 목록 (M3-a 읽기 전용).
	 *
	 * @param totalAmount
	 *            받을 돈 합계 (원, 취소 제외)
	 * @param paidAmount
	 *            받은 돈 합계 (원, 취소 제외)
	 */
	public record MonthView(YearMonth month, boolean thisMonth, List<ChargeRow> rows, long totalAmount,
			long paidAmount) {

		public String monthText() {
			return month.getYear() + "년 " + month.getMonthValue() + "월";
		}

		public String previousMonth() {
			return month.minusMonths(1).toString();
		}

		public String nextMonth() {
			return month.plusMonths(1).toString();
		}

		public String totalText() {
			return Won.format(totalAmount);
		}

		public String paidText() {
			return Won.format(paidAmount);
		}

		/** 남은 돈 (더 받은 청구가 있어도 0 아래로 내려가지 않게 청구마다 남은 금액의 합) */
		public String balanceText() {
			return Won.format(rows.stream().filter(r -> !r.canceled()).mapToLong(ChargeRow::balance).sum());
		}

	}

	/**
	 * 목록의 한 줄.
	 *
	 * @param sharedMonth
	 *            같은 호실에 이 달 청구가 둘 이상 있음 (한 달 안에 세입자가 바뀜, M3 결정 3)
	 */
	public record ChargeRow(long chargeId, String unitName, String tenantName, LocalDate dueDate, long totalAmount,
			long paidAmount, ChargeStatus status, boolean sharedMonth) {

		public String dueDateText() {
			return dueDate.getMonthValue() + "월 " + dueDate.getDayOfMonth() + "일";
		}

		public String totalText() {
			return Won.format(totalAmount);
		}

		public String paidText() {
			return Won.format(paidAmount);
		}

		public long balance() {
			return Math.max(0, totalAmount - paidAmount);
		}

		/** 더 받은 금액 (M3 결정 10). 없으면 null */
		public String overpaidText() {
			return paidAmount > totalAmount ? Won.format(paidAmount - totalAmount) : null;
		}

		public boolean canceled() {
			return status == ChargeStatus.CANCELED;
		}

		public String statusLabel() {
			return status.getLabel();
		}

		/** 상태 표시 색 (글자와 함께 쓴다. 색만으로 구분하지 않는다) */
		public String statusBadge() {
			return switch (status) {
				case SCHEDULED, ISSUED, TENANT_REPORTED -> "badge badge-upcoming";
				case PARTIALLY_PAID -> "badge badge-partial";
				case PAID -> "badge badge-occupied";
				case OVERDUE -> "badge badge-overdue";
				case CANCELED -> "badge badge-vacant";
			};
		}

		@Override
		public String toString() {
			return "ChargeRow[chargeId=" + chargeId + ", status=" + status + "]";
		}

	}

}
