package com.rentkeeper.common.mail;

/**
 * 메일 발송 수단. 개발 중에는 로그로 출력하는 {@link LoggingEmailSender}(local 프로필 전용)를 쓰고, 실제
 * 발송(SMTP/SES) 구현은 M5에서 추가한다 (ADR-010).
 * <p>
 * 업무 코드는 이 인터페이스를 직접 부르지 말고 {@link EmailDispatcher}를 쓴다. 그래야 DB 트랜잭션이 커밋된 뒤에만
 * 발송된다.
 */
public interface EmailSender {

	void send(EmailMessage message);

}
