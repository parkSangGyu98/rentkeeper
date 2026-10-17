package com.rentkeeper.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 메일 내용을 검증하는 테스트에서 개발용 로그 구현 대신 쓰는 발송 구현.
 */
@TestConfiguration(proxyBeanMethods = false)
public class RecordingEmailSenderConfiguration {

	@Bean
	@Primary
	RecordingEmailSender recordingEmailSender() {
		return new RecordingEmailSender();
	}

}
