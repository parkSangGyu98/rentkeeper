package com.rentkeeper.common.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 개발용: 메일을 보내지 않고 내용을 콘솔 로그에 출력한다. 인증·재설정 링크(토큰 원문)가 로그에 남으므로 <strong>local
 * 프로필에서만</strong> 빈으로 등록된다 (ADR-010). 운영(prod)에서는 {@link ProdEmailSenderGuard}가
 * 이 구현이 쓰이지 않는지 한 번 더 확인한다.
 * <p>
 * 로그는 {@value #LOGGER_NAME} 로거로 남는다. bootRun 콘솔에서 이 이름으로 찾으면 링크를 볼 수 있다.
 */
@Component
@Profile("local")
public class LoggingEmailSender implements EmailSender {

	public static final String LOGGER_NAME = "rentkeeper.dev-mail";

	private static final Logger log = LoggerFactory.getLogger(LOGGER_NAME);

	@Override
	public void send(EmailMessage message) {
		log.info("""

				===== [개발용 메일 - 실제로 발송되지 않음] =====
				받는 사람: {}
				제목: {}
				-----
				{}
				==============================================""", message.to(), message.subject(), message.body());
	}

}
