package com.rentkeeper.billing.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.rentkeeper.billing.application.ChargeExceptions.NotReportedException;
import com.rentkeeper.billing.application.ChargeExceptions.ReasonInvalidException;
import com.rentkeeper.billing.domain.ChangeActor;
import com.rentkeeper.billing.domain.Charge;
import com.rentkeeper.billing.domain.ChargeRepository;
import com.rentkeeper.billing.domain.ChargeStatus;
import com.rentkeeper.billing.domain.ChargeStatusHistory;
import com.rentkeeper.billing.domain.ChargeStatusHistoryRepository;
import com.rentkeeper.common.error.ResourceNotFoundException;

/**
 * 세입자의 "입금했어요" 신고와 임대인의 반려 (PAY-3, M4 결정 7·10). 신고 확인은 입금 기록(PaymentService)으로
 * 한다(결정 11).
 * <ul>
 * <li>신고: 세입자 링크가 가리키는 계약의 청구만. 행을 잠그고 처리해 연타·동시 요청에도 한 번만 반영한다. 이미 신고한 상태면
 * 아무것도 바꾸지 않는다(멱등). 반려 뒤 다시 신고할 수 있다</li>
 * <li>반려: 임대인. 화면이 보던 version과 다르면 저장하지 않는다(ChargeChanges, ADR-025). 사유는 상태
 * 이력에만 남는다</li>
 * </ul>
 */
@Service
@Transactional
public class TenantReportService {

	static final String REASON_REPORTED = "세입자 입금 신고";

	/** 반려 이력의 사유 앞부분. 세입자 화면이 "아직 확인하지 못했어요"를 보일지 이것으로 안다 */
	static final String REJECT_PREFIX = "신고 반려: ";

	/** 반려 사유 최대 길이 (앞부분과 합쳐 상태 이력 사유 200자 안) */
	public static final int REJECT_REASON_MAX_LENGTH = ChargeStatusHistory.REASON_MAX_LENGTH - REJECT_PREFIX.length();

	/** 세입자 신고 결과 */
	public enum ReportOutcome {

		/** 새로 알렸다 */
		REPORTED,

		/** 이미 알린 상태였다 (아무것도 바꾸지 않음) */
		ALREADY_REPORTED,

		/** 이미 다 확인됐다 (아무것도 바꾸지 않음) */
		ALREADY_PAID

	}

	private final ChargeRepository charges;

	private final ChargeStatusHistoryRepository statusHistories;

	private final ChargeChanges changes;

	private final Clock clock;

	private final ApplicationEventPublisher events;

	TenantReportService(ChargeRepository charges, ChargeStatusHistoryRepository statusHistories, ChargeChanges changes,
			Clock clock, ApplicationEventPublisher events) {
		this.events = events;
		this.charges = charges;
		this.statusHistories = statusHistories;
		this.changes = changes;
		this.clock = clock;
	}

	/**
	 * 세입자가 "입금했어요"를 누름.
	 *
	 * @param tenantLinkId
	 *            세입자가 쓴 링크 ID (이력의 actor_id)
	 * @throws ResourceNotFoundException
	 *             링크의 계약이 아닌 청구, 취소한 청구(세입자 화면에 보이지 않는다)
	 */
	public ReportOutcome report(long tenantLinkId, long landlordId, long leaseId, long chargeId) {
		Charge charge = charges.lockForTenant(chargeId, leaseId, landlordId)
				.orElseThrow(() -> new ResourceNotFoundException("charge"));
		switch (charge.getStatus()) {
			case CANCELED, SCHEDULED -> throw new ResourceNotFoundException("charge");
			case PAID -> {
				return ReportOutcome.ALREADY_PAID;
			}
			case TENANT_REPORTED -> {
				return ReportOutcome.ALREADY_REPORTED;
			}
			default -> {
				Instant now = clock.instant();
				charge.reportByTenant(LocalDate.now(clock), now)
						.ifPresent(change -> statusHistories.save(ChargeStatusHistory.of(charge, change,
								ChangeActor.tenant(tenantLinkId), REASON_REPORTED, now)));
				charges.flush();
				events.publishEvent(new TenantReportedEvent(landlordId, leaseId, chargeId));
				return ReportOutcome.REPORTED;
			}
		}
	}

	/**
	 * 임대인이 세입자 신고를 반려함 (통장에 입금이 없음). 납부일이 지났으면 납부일 지남이 된다.
	 *
	 * @return 반려한 뒤의 상태
	 * @throws ReasonInvalidException
	 *             사유가 비었거나 {@value #REJECT_REASON_MAX_LENGTH}자 초과
	 * @throws NotReportedException
	 *             신고가 이미 처리됐을 때
	 */
	public ChargeStatus reject(long landlordId, long chargeId, long seenVersion, String reason) {
		String trimmed = reason == null ? "" : reason.strip();
		if (trimmed.isEmpty() || trimmed.codePointCount(0, trimmed.length()) > REJECT_REASON_MAX_LENGTH) {
			throw new ReasonInvalidException();
		}
		Charge charge = changes.start(landlordId, chargeId, seenVersion);
		if (charge.getStatus() != ChargeStatus.TENANT_REPORTED) {
			throw new NotReportedException();
		}
		long versionBefore = charge.getVersion();
		Instant now = clock.instant();
		charge.rejectTenantReport(LocalDate.now(clock), now)
				.ifPresent(change -> statusHistories.save(ChargeStatusHistory.of(charge, change,
						ChangeActor.landlord(landlordId), REJECT_PREFIX + trimmed, now)));
		changes.finish(charge, versionBefore);
		return charge.getStatus();
	}

	/** 그 이력 사유가 반려인지 (세입자 화면 안내용) */
	static boolean isRejection(String reason) {
		return reason != null && reason.startsWith(REJECT_PREFIX);
	}

}
