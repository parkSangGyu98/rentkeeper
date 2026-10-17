package com.rentkeeper.auth.mail;

import org.springframework.stereotype.Component;

import com.rentkeeper.common.config.AppProperties;
import com.rentkeeper.common.mail.EmailMessage;

/**
 * 계정 관련 메일 문구. 40~60대 임대인이 읽기 쉽게, 무엇을 해야 하는지와 링크 유효 시간을 분명히 쓴다.
 */
@Component
public class AccountEmails {

	private static final String SUBJECT_PREFIX = "[RentKeeper] ";

	private final AppProperties appProperties;

	public AccountEmails(AppProperties appProperties) {
		this.appProperties = appProperties;
	}

	public EmailMessage emailVerification(String to, String name, String token) {
		String link = appProperties.tokenLink("/verify-email", token);
		return new EmailMessage(to, SUBJECT_PREFIX + "이메일 인증을 완료해 주세요", """
				안녕하세요, %s님.

				RentKeeper에 가입해 주셔서 감사합니다.
				아래 링크를 누른 뒤, 열린 화면에서 [인증 완료하기] 버튼을 눌러 주세요.

				%s

				이 링크는 24시간 동안 쓸 수 있어요.
				본인이 가입하지 않았다면 이 메일은 무시하셔도 됩니다.
				""".formatted(name, link));
	}

}
