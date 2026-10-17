package com.rentkeeper.common.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 업무 코드가 메일을 보낼 때 쓰는 입구. 트랜잭션 안에서 호출하면 <strong>커밋된 뒤에만</strong> 발송한다 (ADR-010).
 * <p>
 * 예: 재설정 토큰 저장이 롤백됐는데 메일만 나가면, 사용자는 동작하지 않는 링크를 받게 된다. 트랜잭션 밖에서 호출하면 바로 발송한다.
 * M5에서 Outbox(PRD 4.5)로 옮기기 쉽도록 발송 요청을 이벤트 하나로 모았다.
 */
@Component
public class EmailDispatcher {

	private static final Logger log = LoggerFactory.getLogger(EmailDispatcher.class);

	private final ApplicationEventPublisher eventPublisher;

	// 발송 시점에 꺼낸다. 구현이 없을 때의 기동 실패 안내는 ProdEmailSenderGuard가 맡는다
	private final ObjectProvider<EmailSender> emailSender;

	public EmailDispatcher(ApplicationEventPublisher eventPublisher, ObjectProvider<EmailSender> emailSender) {
		this.eventPublisher = eventPublisher;
		this.emailSender = emailSender;
	}

	public void sendAfterCommit(EmailMessage message) {
		eventPublisher.publishEvent(new EmailRequested(message));
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	void onEmailRequested(EmailRequested event) {
		try {
			emailSender.getObject().send(event.message());
		} catch (RuntimeException e) {
			// 업무 처리는 이미 커밋됐으므로 발송 실패가 사용자 요청을 실패시키지 않게 한다. 메시지 본문(토큰)은 로그에 남기지 않는다
			log.error("메일 발송 실패: {}", event.message(), e);
		}
	}

	record EmailRequested(EmailMessage message) {
	}

}
