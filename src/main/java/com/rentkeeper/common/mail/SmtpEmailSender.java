package com.rentkeeper.common.mail;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * 운영 메일 발송 (M6 결정 5, ADR-010의 "운영용 메일 구현"). SMTP(Brevo 등)로 보낸다. 접속 정보는
 * spring.mail.*, 보내는 사람은 app.mail.from(환경변수 MAIL_FROM).
 * <p>
 * 실패하면 {@link EmailSendException}을 던진다. SMTP 예외 메시지에는 받는 주소가 섞일 수 있어 원래 예외의 종류만
 * 넘긴다.
 */
@Component
@Profile("prod")
public class SmtpEmailSender implements EmailSender {

	private final JavaMailSender mailSender;

	private final String from;

	public SmtpEmailSender(JavaMailSender mailSender, @Value("${app.mail.from:}") String from) {
		if (from == null || from.isBlank()) {
			throw new IllegalStateException("운영 메일의 보내는 사람(app.mail.from, 환경변수 MAIL_FROM)이 필요합니다.");
		}
		this.mailSender = mailSender;
		this.from = from.strip();
	}

	@Override
	public void send(EmailMessage message) {
		SimpleMailMessage mail = new SimpleMailMessage();
		mail.setFrom(from);
		mail.setTo(message.to());
		mail.setSubject(message.subject());
		mail.setText(message.body());
		try {
			mailSender.send(mail);
		} catch (MailException e) {
			throw new EmailSendException(e.getClass().getSimpleName());
		}
	}

	/** SMTP 발송 실패. 메시지에는 원래 예외의 종류만 담는다(주소·본문 없음) */
	public static class EmailSendException extends RuntimeException {

		private static final long serialVersionUID = 1L;

		EmailSendException(String cause) {
			super("smtp send failed: " + cause);
		}

	}

}
