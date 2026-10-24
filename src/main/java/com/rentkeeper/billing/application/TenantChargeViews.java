package com.rentkeeper.billing.application;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import com.rentkeeper.billing.domain.ChargeStatus;
import com.rentkeeper.common.money.Won;

/**
 * 세입자 화면용 받을 월세 (M4 결정 5·6). 임대인 화면과 말을 다르게 쓴다: "연체·미납·납부일 지남" 대신 중립적인 말만
 * 쓴다(PRD 4.5 알림 문구 원칙). 입금 기록의 메모·사유는 담지 않는다(임대인이 쓴 글).
 */
public final class TenantChargeViews {

	private TenantChargeViews() {
	}

	/**
	 * 받을 월세 한 건.
	 *
	 * @param items
	 *            항목 (보낼 월세 화면에서만 채운다. 지난 기록에서는 빈 목록)
	 * @param reportRejected
	 *            마지막 상태 변경이 임대인의 신고 반려였는지 (사유는 담지 않는다, M4 결정 10)
	 */
	public record TenantCharge(long chargeId, YearMonth month, LocalDate dueDate, long totalAmount, long paidAmount,
			ChargeStatus status, List<TenantItem> items, boolean reportRejected) {

		/** "입금했어요"를 누를 수 있는지 (M4 결정 7: 받을 차례·일부 받음·납부일 지남) */
		public boolean canReport() {
			return status == ChargeStatus.ISSUED || status == ChargeStatus.PARTIALLY_PAID
					|| status == ChargeStatus.OVERDUE;
		}

		public boolean reported() {
			return status == ChargeStatus.TENANT_REPORTED;
		}

		/** 반려 안내를 보일지 (다시 신고하거나 확인되면 사라진다) */
		public boolean showRejectedNotice() {
			return reportRejected && canReport();
		}

		/** "10월 월세" (해가 다르면 "2027년 1월 월세") */
		public String title(YearMonth thisMonth) {
			String monthText = month.getYear() == thisMonth.getYear()
					? month.getMonthValue() + "월"
					: month.getYear() + "년 " + month.getMonthValue() + "월";
			return monthText + " 월세";
		}

		public String monthText() {
			return month.getYear() + "년 " + month.getMonthValue() + "월";
		}

		/** "10월 5일까지" */
		public String dueText() {
			return dueDate.getMonthValue() + "월 " + dueDate.getDayOfMonth() + "일까지";
		}

		public String totalText() {
			return Won.format(totalAmount);
		}

		public String paidText() {
			return Won.format(paidAmount);
		}

		/** 보내실 금액 (남은 금액, 0 아래로 내려가지 않는다) */
		public String balanceText() {
			return Won.format(Math.max(0, totalAmount - paidAmount));
		}

		public boolean partlyConfirmed() {
			return paidAmount > 0 && status != ChargeStatus.PAID;
		}

		public boolean paid() {
			return status == ChargeStatus.PAID;
		}

		/** 세입자에게 보일 상태 (임대인 화면의 상태 이름과 다르다) */
		public String statusText() {
			return switch (status) {
				case PAID -> "입금이 확인됐어요";
				case PARTIALLY_PAID -> "일부 확인됐어요";
				case TENANT_REPORTED -> "입금했다고 알려 주셨어요";
				case SCHEDULED, ISSUED, OVERDUE -> "아직 확인 전이에요";
				case CANCELED -> "취소됐어요";
			};
		}

		/** 상태 색 (글자와 함께 쓴다) */
		public String statusBadge() {
			return switch (status) {
				case PAID -> "badge badge-occupied";
				case PARTIALLY_PAID, TENANT_REPORTED -> "badge badge-partial";
				default -> "badge badge-upcoming";
			};
		}

		@Override
		public String toString() {
			return "TenantCharge[chargeId=" + chargeId + ", status=" + status + "]";
		}

	}

	/** 항목 한 줄 (이름·금액만, 메모 없음) */
	public record TenantItem(String label, long amount) {

		public String amountText() {
			return Won.format(amount);
		}

	}

}
