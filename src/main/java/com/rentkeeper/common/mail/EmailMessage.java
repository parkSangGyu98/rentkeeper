package com.rentkeeper.common.mail;

import java.util.Objects;

/**
 * 보낼 메일 한 통. 본문에는 인증·재설정 링크(토큰)가 들어갈 수 있으므로 toString()에 본문을 넣지 않는다.
 */
public record EmailMessage(String to, String subject, String body) {

	public EmailMessage {
		Objects.requireNonNull(to, "to");
		Objects.requireNonNull(subject, "subject");
		Objects.requireNonNull(body, "body");
	}

	@Override
	public String toString() {
		return "EmailMessage[to=" + to + ", subject=" + subject + ", body=***]";
	}

}
