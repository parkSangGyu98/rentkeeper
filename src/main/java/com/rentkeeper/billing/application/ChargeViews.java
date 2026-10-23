package com.rentkeeper.billing.application;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.rentkeeper.billing.domain.ChangeActor;
import com.rentkeeper.billing.domain.ChargeItemHistory;
import com.rentkeeper.billing.domain.ChargeItemType;
import com.rentkeeper.billing.domain.ChargeStatus;
import com.rentkeeper.common.money.Won;

/** 받을 월세 화면용 값 (화면 문자열은 여기서 만든다: Thymeleaf 3.1은 템플릿의 정적 호출을 막는다) */
public final class ChargeViews {

	private ChargeViews() {
	}

	/**
	 * 한 달 받을 월세 목록.
	 *
	 * @param filter
	 *            고른 상태. rows는 이 상태만 담는다
	 * @param tabs
	 *            상태 고르기 (건수는 고른 상태와 관계없이 달 전체 기준)
	 * @param totalAmount
	 *            받을 돈 합계 (원, 취소 제외, 고른 상태와 관계없이 달 전체, M3-b 결정 12)
	 * @param paidAmount
	 *            받은 돈 합계 (원, 취소 제외, 달 전체)
	 * @param balanceAmount
	 *            남은 돈 합계 (원, 달 전체). 더 받은 청구가 있어도 0 아래로 내려가지 않게 청구마다 남은 금액을 더한다
	 * @param monthEmpty
	 *            이 달에 받을 월세가 하나도 없음 (고른 상태 때문에 비었는지와 구분한다)
	 */
	public record MonthView(YearMonth month, boolean thisMonth, StatusFilter filter, List<FilterTab> tabs,
			List<ChargeRow> rows, long totalAmount, long paidAmount, long balanceAmount, boolean monthEmpty) {

		public String monthText() {
			return month.getYear() + "년 " + month.getMonthValue() + "월";
		}

		/** 이전 달 주소 (고른 상태는 그대로) */
		public String previousHref() {
			return listHref(month.minusMonths(1), filter);
		}

		public String nextHref() {
			return listHref(month.plusMonths(1), filter);
		}

		/** 이번 달 주소 (고른 상태는 그대로) */
		public String thisMonthHref() {
			return filter == StatusFilter.ALL ? "/app/charges" : "/app/charges?status=" + filter.key();
		}

		public String filterLabel() {
			return filter.label();
		}

		public String totalText() {
			return Won.format(totalAmount);
		}

		public String paidText() {
			return Won.format(paidAmount);
		}

		public String balanceText() {
			return Won.format(balanceAmount);
		}

	}

	/** 목록 주소. 전체면 status를 붙이지 않는다 */
	static String listHref(YearMonth month, StatusFilter filter) {
		String href = "/app/charges?month=" + month;
		return filter == StatusFilter.ALL ? href : href + "&status=" + filter.key();
	}

	/**
	 * 목록의 상태 고르기 (M3-b 결정 12). 주소의 status 값으로 고른다. "받을 차례"는 상태 표시 색과 같이 준비 중·받을
	 * 차례·세입자가 입금했다고 함을 묶는다.
	 */
	public enum StatusFilter {

		ALL("전체", EnumSet.allOf(ChargeStatus.class)),

		ISSUED("받을 차례", EnumSet.of(ChargeStatus.SCHEDULED, ChargeStatus.ISSUED, ChargeStatus.TENANT_REPORTED)),

		PARTIALLY_PAID("일부 받음", EnumSet.of(ChargeStatus.PARTIALLY_PAID)),

		PAID("다 받음", EnumSet.of(ChargeStatus.PAID)),

		OVERDUE("납부일 지남", EnumSet.of(ChargeStatus.OVERDUE)),

		CANCELED("취소함", EnumSet.of(ChargeStatus.CANCELED));

		private final String label;

		private final Set<ChargeStatus> statuses;

		StatusFilter(String label, Set<ChargeStatus> statuses) {
			this.label = label;
			this.statuses = statuses;
		}

		public String label() {
			return label;
		}

		/** 주소에 쓰는 값 (예: "partially-paid") */
		public String key() {
			return name().toLowerCase(Locale.ROOT).replace('_', '-');
		}

		public boolean matches(ChargeStatus status) {
			return statuses.contains(status);
		}

		/** 주소의 값을 읽는다. 없거나 모르는 값이면 전체 */
		public static StatusFilter parse(String raw) {
			String key = raw == null ? "" : raw.strip();
			for (StatusFilter f : values()) {
				if (f.key().equals(key)) {
					return f;
				}
			}
			return ALL;
		}

	}

	/** 상태 고르기 한 칸 */
	public record FilterTab(StatusFilter filter, long count, boolean selected, String href) {

		public String label() {
			return filter.label();
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
	 * @param statusHistory
	 *            상태가 바뀐 기록 (오래된 순)
	 * @param itemHistory
	 *            항목을 추가·고침·지운 기록 (오래된 순)
	 */
	public record ChargeDetail(long id, long version, long leaseId, String unitName, String tenantName, YearMonth month,
			LocalDate dueDate, ChargeStatus status, long totalAmount, long paidAmount, String cancelReason,
			List<ItemRow> items, List<PaymentRow> payments, LocalDate earliestPaidOn,
			List<StatusHistoryRow> statusHistory, List<ItemHistoryRow> itemHistory) {

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

	/**
	 * 상태 기록 한 줄.
	 *
	 * @param at
	 *            바뀐 시각 (서비스가 Clock의 시간대(서울)로 바꿔 넘긴다)
	 * @param from
	 *            처음 만들 때는 null
	 * @param reason
	 *            사유 (취소 사유는 임대인이 쓴 글, 나머지는 정해 둔 글). 없으면 null
	 */
	public record StatusHistoryRow(LocalDateTime at, ChargeStatus from, ChargeStatus to, ChangeActor.Type actor,
			String reason) {

		/** 발행 사유 끝에 붙인 개발용 메모 (ChargeIssuer). 임대인이 쓴 취소 사유는 자르지 않도록 이 꼬리말만 뗀다 */
		static final String DEV_NOTE = " — 알림 발송은 M5";

		public String atText() {
			return dateTimeText(at);
		}

		/** "받을 차례 → 다 받음". 처음 만들 때는 "준비 중" */
		public String changeText() {
			return from == null ? to.getLabel() : from.getLabel() + " → " + to.getLabel();
		}

		public String actorText() {
			return ChargeViews.actorText(actor);
		}

		/** 화면에 보일 사유. 개발용 메모는 화면에서만 뗀다 (DB 기록은 그대로) */
		public String reasonText() {
			if (reason == null) {
				return null;
			}
			return reason.endsWith(DEV_NOTE) ? reason.substring(0, reason.length() - DEV_NOTE.length()) : reason;
		}

	}

	/**
	 * 항목 기록 한 줄.
	 *
	 * @param at
	 *            바뀐 시각 (서울)
	 * @param beforeAmount
	 *            고치기·지우기 전 금액 (원, 추가면 null)
	 * @param afterAmount
	 *            추가·고친 뒤 금액 (원, 지우기면 null)
	 */
	public record ItemHistoryRow(LocalDateTime at, ChargeItemHistory.Action action, String label, Long beforeAmount,
			Long afterAmount, ChangeActor.Type actor) {

		public String atText() {
			return dateTimeText(at);
		}

		/** "추가: 월세 500,000원" / "고침: 관리비 50,000원 → 60,000원" / "지움: 청소비 30,000원" */
		public String changeText() {
			return switch (action) {
				case ADD -> "추가: " + label + " " + Won.format(afterAmount);
				case CHANGE -> "고침: " + label + " " + Won.format(beforeAmount) + " → " + Won.format(afterAmount);
				case REMOVE -> "지움: " + label + " " + Won.format(beforeAmount);
			};
		}

		public String actorText() {
			return ChargeViews.actorText(actor);
		}

	}

	private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy년 M월 d일 HH:mm");

	/** "2026년 10월 5일 03:00" */
	static String dateTimeText(LocalDateTime at) {
		return DATE_TIME.format(at);
	}

	/** 누가 바꿨는지 */
	static String actorText(ChangeActor.Type actor) {
		return switch (actor) {
			case LANDLORD -> "내가 바꿈";
			case TENANT -> "세입자";
			case SYSTEM -> "자동";
		};
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
