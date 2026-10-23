package com.rentkeeper.billing.application;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import com.rentkeeper.billing.domain.ChargeItemType;
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

		public String statusBadge() {
			return badge(status);
		}

		@Override
		public String toString() {
			return "ChargeRow[chargeId=" + chargeId + ", status=" + status + "]";
		}

	}

	/**
	 * 받을 월세 상세.
	 *
	 * @param version
	 *            화면이 보는 version (폼의 숨은 칸으로 보내 낙관적 락에 쓴다)
	 * @param earliestPaidOn
	 *            받은 날로 고를 수 있는 가장 이른 날 (계약 시작월 1일)
	 */
	public record ChargeDetail(long id, long version, long leaseId, String unitName, String tenantName, YearMonth month,
			LocalDate dueDate, ChargeStatus status, long totalAmount, long paidAmount, String cancelReason,
			List<ItemRow> items, List<PaymentRow> payments, LocalDate earliestPaidOn) {

		public String monthText() {
			return month.getYear() + "년 " + month.getMonthValue() + "월";
		}

		public String dueDateText() {
			return dateText(dueDate);
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

		public String balanceText() {
			return Won.format(balance());
		}

		/** 더 받은 금액. 없으면 null */
		public String overpaidText() {
			return paidAmount > totalAmount ? Won.format(paidAmount - totalAmount) : null;
		}

		public String statusLabel() {
			return status.getLabel();
		}

		public String statusBadge() {
			return badge(status);
		}

		public boolean canceled() {
			return status == ChargeStatus.CANCELED;
		}

		/** 취소 링크를 보여 줄지: 입금이 없고 아직 취소하지 않았을 때 (M3 결정 7) */
		public boolean canCancel() {
			return !canceled() && paidAmount == 0 && status != ChargeStatus.PAID;
		}

		/** 입금 버튼을 보여 줄지: 다 받았거나 취소한 청구는 보이지 않는다 */
		public boolean canPay() {
			return status != ChargeStatus.PAID && status != ChargeStatus.CANCELED;
		}

		public List<PaymentRow> activePayments() {
			return payments.stream().filter(p -> !p.deleted()).toList();
		}

		public List<PaymentRow> deletedPayments() {
			return payments.stream().filter(PaymentRow::deleted).toList();
		}

		public String earliestPaidOnText() {
			return dateText(earliestPaidOn);
		}

		@Override
		public String toString() {
			return "ChargeDetail[id=" + id + ", status=" + status + "]";
		}

	}

	/** 상세의 항목 한 줄 */
	public record ItemRow(long id, ChargeItemType type, String label, long amount, String memo) {

		public String typeLabel() {
			return type.getLabel();
		}

		public String amountText() {
			return Won.format(amount);
		}

	}

	/** 상세의 입금 기록 한 줄 */
	public record PaymentRow(long id, LocalDate paidOn, long amount, String memo, boolean deleted, String deleteReason,
			Long replacesId) {

		public String paidOnText() {
			return dateText(paidOn);
		}

		public String amountText() {
			return Won.format(amount);
		}

		@Override
		public String toString() {
			return "PaymentRow[id=" + id + "]";
		}

	}

	/** "2026년 10월 5일" */
	public static String dateText(LocalDate date) {
		return date.getYear() + "년 " + date.getMonthValue() + "월 " + date.getDayOfMonth() + "일";
	}

	/** 상태 표시 색 (글자와 함께 쓴다. 색만으로 구분하지 않는다) */
	static String badge(ChargeStatus status) {
		return switch (status) {
			case SCHEDULED, ISSUED, TENANT_REPORTED -> "badge badge-upcoming";
			case PARTIALLY_PAID -> "badge badge-partial";
			case PAID -> "badge badge-occupied";
			case OVERDUE -> "badge badge-overdue";
			case CANCELED -> "badge badge-vacant";
		};
	}

}
