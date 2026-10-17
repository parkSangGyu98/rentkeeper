package com.rentkeeper.common.mail;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * 운영(prod)에서 메일 구현이 없거나 개발용 로그 구현이 쓰이면 앱을 띄우지 않는다 (ADR-010).
 * <p>
 * 링크가 조용히 버려지거나 토큰이 운영 로그에 남는 것보다, 배포 직후 기동 실패로 바로 드러나는 편이 안전하다. 실제 발송 구현은 M5에서
 * 추가하며, 운영 배포(M6)는 그 뒤다.
 */
@Configuration(proxyBeanMethods = false)
@Profile("prod")
class ProdEmailSenderGuard {

	ProdEmailSenderGuard(ObjectProvider<EmailSender> emailSenders) {
		EmailSender sender = emailSenders.getIfUnique();
		if (sender == null) {
			throw new IllegalStateException("운영(prod)용 메일 발송 구현(EmailSender)이 없습니다. 실제 발송 구현은 M5에서 추가합니다 (ADR-010).");
		}
		if (sender instanceof LoggingEmailSender) {
			throw new IllegalStateException("운영(prod)에서 개발용 LoggingEmailSender를 쓸 수 없습니다. 토큰이 로그에 남습니다 (ADR-010).");
		}
	}

}
