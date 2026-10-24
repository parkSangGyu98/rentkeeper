package com.rentkeeper.notification.application;

import java.time.Clock;
import java.time.LocalDate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.rentkeeper.common.ops.LoggingOpsAlertNotifier;
import com.rentkeeper.common.ops.OpsAlertNotifier;
import com.rentkeeper.notification.domain.SkipReason;

/**
 * 운영 알림 메일 (M5 결정 16). 지금까지처럼 ERROR 로그를 남기고, 코드마다 하루 한 통의 메일 행을
 * 넣는다(OPS_ALERT:{코드}:{날짜}). 메일 본문에는 코드만 넣고 건수·종류는 로그에서 본다(개인정보 없음).
 * OPS_ALERT_EMAIL이 비면 "보내지 않음(NO_ADDRESS)".
 * <p>
 * 부른 쪽 트랜잭션과 따로(새 트랜잭션) 넣는다: 업무가 롤백돼도 알림은 남고, 알림을 넣다 실패해도 업무를 깨지 않는다. 알림 발송
 * 실패(NOTIFICATION_ALERT)가 다시 운영 알림을 불러도 하루 한 통이라 끝이 있다.
 */
@Component
@Primary
class OutboxOpsAlertNotifier implements OpsAlertNotifier {

	private static final Logger log = LoggerFactory.getLogger(OutboxOpsAlertNotifier.class);

	private final LoggingOpsAlertNotifier logging;

	private final NotificationOutbox outbox;

	private final NotificationProperties properties;

	private final Clock clock;

	private final TransactionTemplate newTransaction;

	OutboxOpsAlertNotifier(LoggingOpsAlertNotifier logging, NotificationOutbox outbox,
			NotificationProperties properties, Clock clock, PlatformTransactionManager transactionManager) {
		this.logging = logging;
		this.outbox = outbox;
		this.properties = properties;
		this.clock = clock;
		this.newTransaction = new TransactionTemplate(transactionManager);
		this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	@Override
	public void alert(String code, String summary) {
		logging.alert(code, summary);
		NotificationRequest request = NotificationRequest.ops(code, "OPS_ALERT:" + code + ":" + LocalDate.now(clock));
		try {
			newTransaction.executeWithoutResult(status -> {
				if (properties.opsAlertEmail() == null) {
					outbox.enqueueSkipped(request, SkipReason.NO_ADDRESS);
				} else {
					outbox.enqueue(request);
				}
			});
		} catch (RuntimeException e) {
			log.error("ops alert mail queue failed: code={}, error={}", code, e.getClass().getSimpleName());
		}
	}

}
