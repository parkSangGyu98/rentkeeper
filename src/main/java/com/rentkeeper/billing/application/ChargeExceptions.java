package com.rentkeeper.billing.application;

import com.rentkeeper.common.error.BusinessException;
import com.rentkeeper.common.error.ErrorCode;

/** 받을 월세·입금 화면에서 안내 문구로 바꿔 보여 줄 업무 규칙 위반 */
public final class ChargeExceptions {

	private ChargeExceptions() {
	}

	/**
	 * 화면이 보던 뒤로 받을 월세가 바뀌었다 (낙관적 락, M3-b 결정 4). 다른 창에서 고쳤거나 납부일 지남 배치가 상태를 바꿨다.
	 * 입력값을 자동으로 다시 보내지 않고 최신 상세로 돌려보낸다.
	 */
	public static class ChargeChangedException extends BusinessException {

		public ChargeChangedException() {
			super(ErrorCode.CHARGE_CHANGED, "charge changed since the form was shown");
		}

		public ChargeChangedException(Throwable cause) {
			super(ErrorCode.CHARGE_CHANGED, "charge changed concurrently", cause);
		}

	}

	/** 취소한 받을 월세는 고칠 수 없다 */
	public static class ChargeCanceledException extends BusinessException {

		ChargeCanceledException() {
			super(ErrorCode.CHARGE_CANCELED, "charge canceled");
		}

	}

	/** 입금 입력값이 규칙 밖이다. 화면이 칸 옆에 안내한다 */
	public static class PaymentInputException extends BusinessException {

		public enum Problem {

			/** 0원 이하 */
			AMOUNT_TOO_SMALL,

			/** 10억 원 초과 */
			AMOUNT_TOO_LARGE,

			/** 오늘보다 뒤 */
			DATE_IN_FUTURE,

			/** 계약 시작월 1일 전 (M3 결정 9) */
			DATE_BEFORE_LEASE,

			/** 지우거나 고치는 사유가 비었거나 200자 초과 (M3 결정 11) */
			REASON_INVALID

		}

		private final Problem problem;

		PaymentInputException(Problem problem) {
			super(ErrorCode.PAYMENT_INVALID, "invalid payment: " + problem);
			this.problem = problem;
		}

		public Problem problem() {
			return problem;
		}

	}

	/** 이미 지운 입금 기록은 다시 지우거나 고칠 수 없다 */
	public static class PaymentDeletedException extends BusinessException {

		PaymentDeletedException() {
			super(ErrorCode.PAYMENT_INVALID, "payment already deleted");
		}

	}

	/** 남은 금액보다 많이 기록하려 한다. 한 번 더 확인받는다 (M3-b 결정 3) */
	public static class OverpaymentException extends BusinessException {

		private final long over;

		OverpaymentException(long over) {
			super(ErrorCode.PAYMENT_OVERPAID, "payment exceeds balance");
			this.over = over;
		}

		/** 남은 금액보다 많은 만큼 (원) */
		public long over() {
			return over;
		}

	}

}
