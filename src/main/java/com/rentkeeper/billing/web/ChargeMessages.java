package com.rentkeeper.billing.web;

import java.time.YearMonth;

import com.rentkeeper.billing.application.ChargeExceptions.MonthUnavailableException;
import com.rentkeeper.billing.application.ChargeExceptions.PaymentInputException;
import com.rentkeeper.billing.application.ChargeViews;
import com.rentkeeper.billing.domain.ChargeStatus;
import com.rentkeeper.common.money.Won;

/**
 * 받을 월세 화면의 안내 문구 (40~60대 임대인 기준: 무엇이 일어났고 무엇을 하면 되는지 한두 문장, "충돌"·"버전" 같은 말은 쓰지
 * 않는다). 문구는 M3-b 결정 4와 입력 안내 표로 확정했고 테스트가 고정한다.
 */
final class ChargeMessages {

	/** 낙관적 락: 그 사이 다 받음이 됐을 때 */
	static final String ALREADY_PAID = "이미 다 받은 것으로 기록돼 있어요. 아래 입금 기록을 확인해 주세요.";

	/** 낙관적 락: 그 밖 (다른 창에서 고침, 납부일 지남 배치) */
	static final String CHANGED = "이 월세 내용이 방금 바뀌었어요. 다른 창에서 고쳤거나, 납부일이 지나 상태가 자동으로 바뀐 경우예요. "
			+ "아래 최신 내용을 확인하고, 필요하면 다시 기록해 주세요.";

	static final String CANCELED = "취소한 받을 월세는 고칠 수 없어요.";

	static final String REASON_REQUIRED = "사유를 1~200자로 입력해 주세요 (예: 금액을 잘못 입력함)";

	static final String PAYMENT_DELETED = "이미 지운 입금 기록이에요. 아래 최신 내용을 확인해 주세요.";

	static final String ZERO_TOTAL = "받을 금액이 0원이 돼요. 이 달 월세를 받지 않으려면 [받을 월세 취소]를 써 주세요.";

	static final String LAST_ITEM = "마지막 항목은 지울 수 없어요. 이 달 월세를 받지 않으려면 [받을 월세 취소]를 써 주세요.";

	static final String HAS_PAYMENTS = "입금 기록이 있어 취소할 수 없어요. 입금 기록을 먼저 지워 주세요.";

	/** 취소 확인 화면 (M3 결정 8) */
	static final String CANCEL_GUIDE = "이 달은 새로 만들 수 없어요. 금액이 틀렸다면 취소 대신 항목을 고치세요.";

	static final String CANCEL_REASON_REQUIRED = "취소 사유를 1~200자로 입력해 주세요 (예: 잘못 만듦)";

	static final String CREATE_ZERO = "월세와 관리비가 모두 0원이에요. 받을 금액을 입력해 주세요.";

	private ChargeMessages() {
	}

	/** "지금 상태는 '받을 차례'예요." / "'일부 받음'이에요." (받침에 따라) */
	static String stateIs(ChargeStatus status) {
		String label = status.getLabel();
		char last = label.charAt(label.length() - 1);
		boolean noFinal = last >= 0xAC00 && last <= 0xD7A3 && (last - 0xAC00) % 28 == 0;
		return "'" + label + "'" + (noFinal ? "예요." : "이에요.");
	}

	/** 직접 만들 수 없는 달 */
	static String monthUnavailable(MonthUnavailableException.Reason reason) {
		return switch (reason) {
			case EXISTS -> "이 달 받을 월세는 이미 있어요. 금액이 틀렸다면 그 달 받을 월세에서 항목을 고쳐 주세요.";
			case CANCELED -> "이 달 받을 월세는 취소한 적이 있어 새로 만들 수 없어요.";
			case OUT_OF_RANGE -> "계약 기간 안의 달(다음 달까지)만 만들 수 있어요.";
		};
	}

	static String created(YearMonth month) {
		return month.getYear() + "년 " + month.getMonthValue() + "월 받을 월세를 만들었어요.";
	}

	static String itemsChanged(String what, long total, ChargeStatus status) {
		return what + " 받을 돈은 " + Won.format(total) + ", 지금 상태는 " + stateIs(status);
	}

	static String canceledDone() {
		return "받을 월세를 취소했어요. 이 달은 새로 만들 수 없어요.";
	}

	/** 화면이 보던 뒤로 바뀌었을 때 (지금 상태에 맞춰 고른다) */
	static String changed(ChargeStatus now) {
		return now == ChargeStatus.PAID ? ALREADY_PAID : CHANGED;
	}

	static String paymentInput(PaymentInputException e, ChargeViews.ChargeDetail detail) {
		return switch (e.problem()) {
			case AMOUNT_TOO_SMALL -> "받은 금액을 1원 이상 입력해 주세요 (예: 500,000)";
			case AMOUNT_TOO_LARGE -> "한 번에 기록할 수 있는 금액은 10억 원까지예요";
			case DATE_IN_FUTURE -> "받은 날은 오늘이나 그 전 날짜로 골라 주세요";
			case DATE_BEFORE_LEASE -> "받은 날은 계약이 시작된 달(" + detail.earliestPaidOnText() + ") 이후로 골라 주세요";
			case REASON_INVALID -> REASON_REQUIRED;
		};
	}

	/** 안내를 붙일 칸 */
	static String field(PaymentInputException e) {
		return switch (e.problem()) {
			case AMOUNT_TOO_SMALL, AMOUNT_TOO_LARGE -> "amount";
			case DATE_IN_FUTURE, DATE_BEFORE_LEASE -> "paidOn";
			case REASON_INVALID -> "reason";
		};
	}

	/** 남은 금액보다 많을 때 한 번 더 묻는다 */
	static String overpay(long over) {
		return "남은 금액보다 " + Won.format(over) + " 많아요. 이대로 기록할까요? 맞으면 [기록하기]를 한 번 더 눌러 주세요.";
	}

	static String deleted(ChargeStatus status) {
		return "입금 기록을 지웠어요. 지금 상태는 " + stateIs(status);
	}

	static String corrected(ChargeStatus status) {
		return "입금 기록을 고쳤어요. 지금 상태는 " + stateIs(status);
	}

	static String recorded(ChargeStatus status, long balance) {
		if (status == ChargeStatus.PAID) {
			return "입금을 기록했어요. 이 달 월세를 다 받았어요.";
		}
		return "입금을 기록했어요. 남은 금액은 " + Won.format(balance) + "이에요.";
	}

}
