package com.rentkeeper.common.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * 운영 메일 SMTP 발송 (M6 커밋 2, 결정 5). 보내는 사람·받는 사람·제목·본문을 그대로 넘기고, 실패하면 주소·본문 없이 예외
 * 종류만 알린다. prod에서는 이 구현이 있으면 기존 가드(ADR-010)를 통과한다.
 */
class SmtpEmailSenderTest {

	private final JavaMailSender mailSender = mock(JavaMailSender.class);

	@Test
	void sendsWithTheConfiguredSender() {
		new SmtpEmailSender(mailSender, " RentKeeper <no-reply@example.com> ")
				.send(new EmailMessage("kim@example.com", "제목", "본문"));

		ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
		verify(mailSender).send(sent.capture());
		assertThat(sent.getValue().getFrom()).isEqualTo("RentKeeper <no-reply@example.com>");
		assertThat(sent.getValue().getTo()).containsExactly("kim@example.com");
		assertThat(sent.getValue().getSubject()).isEqualTo("제목");
		assertThat(sent.getValue().getText()).isEqualTo("본문");
	}

	@Test
	void failureMessageCarriesNoAddressOrBody() {
		doThrow(new MailSendException("550 rejected kim@example.com 본문")).when(mailSender)
				.send(any(SimpleMailMessage.class));
		SmtpEmailSender sender = new SmtpEmailSender(mailSender, "no-reply@example.com");

		assertThatThrownBy(() -> sender.send(new EmailMessage("kim@example.com", "제목", "비밀본문")))
				.isInstanceOf(SmtpEmailSender.EmailSendException.class)
				.hasMessage("smtp send failed: MailSendException").hasNoCause();
	}

	@Test
	void senderAddressIsRequired() {
		assertThatThrownBy(() -> new SmtpEmailSender(mailSender, " ")).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("MAIL_FROM");
	}

	@Test
	void prodStartsWithTheSmtpSender() {
		new ApplicationContextRunner().withBean(JavaMailSender.class, () -> mailSender)
				.withUserConfiguration(SmtpEmailSender.class, ProdEmailSenderGuard.class)
				.withPropertyValues("spring.profiles.active=prod", "app.mail.from=no-reply@example.com")
				.run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(context).hasSingleBean(SmtpEmailSender.class);
				});
	}

	@Test
	void smtpSenderIsOnlyForProd() {
		new ApplicationContextRunner().withBean(JavaMailSender.class, () -> mailSender)
				.withUserConfiguration(SmtpEmailSender.class)
				.withPropertyValues("spring.profiles.active=local", "app.mail.from=no-reply@example.com")
				.run(context -> assertThat(context).doesNotHaveBean(SmtpEmailSender.class));
	}

}
