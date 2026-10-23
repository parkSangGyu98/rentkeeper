package com.rentkeeper.billing.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.rentkeeper.billing.application.ChargeExceptions.ChargeCanceledException;
import com.rentkeeper.billing.application.ChargeExceptions.ChargeChangedException;
import com.rentkeeper.billing.application.ChargeExceptions.OverpaymentException;
import com.rentkeeper.billing.application.ChargeExceptions.PaymentDeletedException;
import com.rentkeeper.billing.application.ChargeExceptions.PaymentInputException;
import com.rentkeeper.billing.application.ChargeExceptions.PaymentInputException.Problem;
import com.rentkeeper.billing.domain.ChangeActor;
import com.rentkeeper.billing.domain.Charge;
import com.rentkeeper.billing.domain.ChargeStatus;
import com.rentkeeper.billing.domain.ChargeStatusHistory;
import com.rentkeeper.billing.domain.ChargeStatusHistoryRepository;
import com.rentkeeper.billing.domain.PaymentRecord;
import com.rentkeeper.billing.domain.PaymentRecordRepository;
import com.rentkeeper.common.error.ResourceNotFoundException;
import com.rentkeeper.lease.application.LeaseService;

/**
 * 입금 기록 (PAY-1·2, ADR-025). 받은 금액은 지우지 않은 입금 기록의 합계로 다시 계산하고, 상태는 청구가 사실에서 다시
 * 계산한다(ADR-023). 화면이 보던 version과 다르거나 같은 순간 다른 작업과 겹치면 저장하지 않는다(M3-b 결정 4). 남의
 * 청구면 404.
 */
@Service
@Transactional
public class PaymentService {

	static final String REASON_RECORDED = "입금 기록";

	static final String REASON_DELETED = "입금 기록 삭제";

	static final String REASON_CORRECTED = "입금 정정";

	private final ChargeChanges changes;

	private final PaymentRecordRepository payments;

	private final ChargeStatusHistoryRepository statusHistories;

	private final LeaseService leaseService;

	private final Clock clock;

	PaymentService(ChargeChanges changes, PaymentRecordRepository payments,
			ChargeStatusHistoryRepository statusHistories, LeaseService leaseService, Clock clock) {
		this.changes = changes;
		this.payments = payments;
		this.statusHistories = statusHistories;
		this.leaseService = leaseService;
		this.clock = clock;
	}

	/**
	 * 입금 입력 (형식 검사를 마친 값).
	 *
	 * @param overpayConfirmed
	 *            남은 금액보다 많아도 기록한다고 확인했는지
	 */
	public record PaymentInput(long amount, LocalDate paidOn, String memo, boolean overpayConfirmed) {

		@Override
		public String toString() {
			return "PaymentInput[amount=" + amount + ", paidOn=" + paidOn + "]";
		}

	}

	/**
	 * 입금을 기록한다.
	 *
	 * @param seenVersion
	 *            화면이 보던 version
	 * @return 기록한 뒤의 상태
	 * @throws ChargeChangedException
	 *             화면이 보던 뒤로 바뀌었을 때 (같은 순간 겹친 경우는 커밋 때
	 *             OptimisticLockingFailureException이 날 수 있다)
	 * @throws ChargeCanceledException
	 *             취소한 청구
	 * @throws PaymentInputException
	 *             금액·받은 날이 규칙 밖
	 * @throws OverpaymentException
	 *             남은 금액보다 많은데 아직 확인받지 않았을 때
	 */
	public ChargeStatus record(long landlordId, long chargeId, long seenVersion, PaymentInput input) {
		Charge charge = changes.start(landlordId, chargeId, seenVersion);
		long versionBefore = charge.getVersion();
		LocalDate today = LocalDate.now(clock);
		validate(landlordId, charge, input.amount(), input.paidOn(), today);
		if (input.amount() > charge.balance() && !input.overpayConfirmed()) {
			throw new OverpaymentException(input.amount() - charge.balance());
		}
		payments.saveAndFlush(PaymentRecord.manual(charge, input.amount(), input.paidOn(), input.memo()));
		recalculate(landlordId, charge, today, REASON_RECORDED);
		changes.finish(charge, versionBefore);
		return charge.getStatus();
	}

	/**
	 * 입금 기록을 지운다 (M3 결정 11): 행은 남기고 지운 시각·사유만 적는다. 받은 금액과 상태를 다시 계산한다(다 받음 → 일부
	 * 받음·받을 차례, 납부일이 지났으면 납부일 지남).
	 *
	 * @return 지운 뒤의 상태
	 * @throws PaymentDeletedException
	 *             이미 지운 기록
	 * @throws PaymentInputException
	 *             사유가 비었거나 200자 초과 (REASON_INVALID)
	 */
	public ChargeStatus delete(long landlordId, long chargeId, long paymentId, long seenVersion, String reason) {
		Charge charge = changes.start(landlordId, chargeId, seenVersion);
		long versionBefore = charge.getVersion();
		PaymentRecord record = activePayment(landlordId, chargeId, paymentId);
		requireReason(reason);
		record.delete(reason, clock.instant());
		payments.saveAndFlush(record);
		recalculate(landlordId, charge, LocalDate.now(clock), REASON_DELETED);
		changes.finish(charge, versionBefore);
		return charge.getStatus();
	}

	/**
	 * 입금 기록을 고친다 (M3 결정 11, M3-b 결정 5): 원래 기록에 지운 표시(사유) + 고친 새 기록(replaces_id). 같은
	 * 금액으로 고쳐도 version은 오른다.
	 *
	 * @return 고친 뒤의 상태
	 * @throws OverpaymentException
	 *             고친 금액이 (원래 기록을 뺀) 남은 금액보다 많은데 아직 확인받지 않았을 때
	 */
	public ChargeStatus correct(long landlordId, long chargeId, long paymentId, long seenVersion, PaymentInput input,
			String reason) {
		Charge charge = changes.start(landlordId, chargeId, seenVersion);
		long versionBefore = charge.getVersion();
		PaymentRecord original = activePayment(landlordId, chargeId, paymentId);
		LocalDate today = LocalDate.now(clock);
		validate(landlordId, charge, input.amount(), input.paidOn(), today);
		requireReason(reason);
		long balanceWithoutOriginal = Math.max(0,
				charge.getTotalAmount() - (charge.getPaidAmount() - original.getAmount()));
		if (input.amount() > balanceWithoutOriginal && !input.overpayConfirmed()) {
			throw new OverpaymentException(input.amount() - balanceWithoutOriginal);
		}
		PaymentRecord corrected = PaymentRecord.correction(charge, original, input.amount(), input.paidOn(),
				input.memo());
		original.delete(reason, clock.instant());
		payments.saveAndFlush(original);
		payments.saveAndFlush(corrected);
		recalculate(landlordId, charge, today, REASON_CORRECTED);
		changes.finish(charge, versionBefore);
		return charge.getStatus();
	}

	private PaymentRecord activePayment(long landlordId, long chargeId, long paymentId) {
		PaymentRecord record = payments.findByIdAndChargeIdAndLandlordId(paymentId, chargeId, landlordId)
				.orElseThrow(() -> new ResourceNotFoundException("payment"));
		if (record.isDeleted()) {
			throw new PaymentDeletedException();
		}
		return record;
	}

	private static void requireReason(String reason) {
		String trimmed = reason == null ? "" : reason.strip();
		if (trimmed.isEmpty() || trimmed.codePointCount(0, trimmed.length()) > PaymentRecord.REASON_MAX_LENGTH) {
			throw new PaymentInputException(Problem.REASON_INVALID);
		}
	}

	/** 금액 1원 ~ 10억 원, 받은 날은 오늘 이하이고 계약 시작월 1일 이후 (M3 결정 9) */
	void validate(long landlordId, Charge charge, long amount, LocalDate paidOn, LocalDate today) {
		if (amount < 1) {
			throw new PaymentInputException(Problem.AMOUNT_TOO_SMALL);
		}
		if (amount > PaymentRecord.AMOUNT_MAX) {
			throw new PaymentInputException(Problem.AMOUNT_TOO_LARGE);
		}
		if (paidOn.isAfter(today)) {
			throw new PaymentInputException(Problem.DATE_IN_FUTURE);
		}
		if (paidOn.isBefore(earliestPaidOn(landlordId, charge.getLeaseId()))) {
			throw new PaymentInputException(Problem.DATE_BEFORE_LEASE);
		}
	}

	/** 받은 날로 고를 수 있는 가장 이른 날 = 계약 시작월 1일 */
	public LocalDate earliestPaidOn(long landlordId, long leaseId) {
		var lease = leaseService.summaries(landlordId, Set.of(leaseId)).get(leaseId);
		if (lease == null) {
			throw new ResourceNotFoundException("lease");
		}
		return YearMonth.from(lease.startDate()).atDay(1);
	}

	/** 받은 금액을 다시 계산하고, 상태가 바뀌면 이력을 남긴다 */
	void recalculate(long landlordId, Charge charge, LocalDate today, String reason) {
		Instant now = clock.instant();
		long paid = payments.activeTotal(charge.getId(), landlordId);
		charge.changePaid(paid, today, now).ifPresent(change -> statusHistories
				.save(ChargeStatusHistory.of(charge, change, ChangeActor.landlord(landlordId), reason, now)));
	}

}
