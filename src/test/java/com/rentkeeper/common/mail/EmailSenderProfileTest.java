package com.rentkeeper.common.mail;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * 개발용 로그 구현(토큰이 로그에 남음)은 local 프로필에서만 쓰이고, 운영(prod)에서는 메일 구현이 없으면 기동하지 않는다
 * (ADR-010).
 */
@ExtendWith(OutputCaptureExtension.class)
class EmailSenderProfileTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withUserConfiguration(LoggingEmailSender.class, ProdEmailSenderGuard.class);

	@Test
	void loggingSenderIsRegisteredOnlyInLocalProfile() {
		// 가드 없이 @Profile("local")만 확인한다 (prod에서는 가드가 기동 자체를 막으므로 따로 확인)
		ApplicationContextRunner senderOnly = new ApplicationContextRunner()
				.withUserConfiguration(LoggingEmailSender.class);
		senderOnly.withPropertyValues("spring.profiles.active=local")
				.run(context -> assertThat(context).hasSingleBean(LoggingEmailSender.class));
		senderOnly.withPropertyValues("spring.profiles.active=prod")
				.run(context -> assertThat(context).doesNotHaveBean(LoggingEmailSender.class));
	}

	@Test
	void prodFailsToStartWithoutEmailSender() {
		runner.withPropertyValues("spring.profiles.active=prod").run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("운영(prod)용 메일 발송 구현");
		});
	}

	@Test
	void prodRefusesLoggingSenderEvenIfRegisteredManually() {
		// 누군가 설정 실수로 @Profile을 우회해 직접 등록한 상황 (EmailSender 타입으로 등록하면 클래스의 @Profile이 적용되지
		// 않는다)
		new ApplicationContextRunner().withBean(EmailSender.class, LoggingEmailSender::new)
				.withUserConfiguration(ProdEmailSenderGuard.class).withPropertyValues("spring.profiles.active=prod")
				.run(context -> {
					assertThat(context).hasFailed();
					assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("LoggingEmailSender");
				});
	}

	@Test
	void prodStartsWithRealSender() {
		EmailSender realSender = message -> {
		};
		new ApplicationContextRunner().withBean(EmailSender.class, () -> realSender)
				.withUserConfiguration(ProdEmailSenderGuard.class).withPropertyValues("spring.profiles.active=prod")
				.run(context -> assertThat(context).hasNotFailed());
	}

	@Test
	void loggingSenderPrintsLinkUnderDevMailLogger(CapturedOutput output) {
		new LoggingEmailSender()
				.send(new EmailMessage("kim@example.com", "인증", "https://example.com/verify-email?token=abc"));

		assertThat(output).contains(LoggingEmailSender.LOGGER_NAME).contains("verify-email?token=abc");
	}

	@Test
	void messageToStringHidesBody() {
		assertThat(new EmailMessage("kim@example.com", "제목", "token=secret").toString()).doesNotContain("secret");
	}

}
