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

	public EmailMessage passwordReset(String to, String name, String token) {
		String link = appProperties.tokenLink("/password-reset/confirm", token);
		return new EmailMessage(to, SUBJECT_PREFIX + "비밀번호 재설정 안내", """
				안녕하세요, %s님.

				비밀번호 재설정을 요청하셨어요.
				아래 링크를 눌러 새 비밀번호를 정해 주세요.

				%s

				이 링크는 30분 동안, 한 번만 쓸 수 있어요.
				본인이 요청하지 않았다면 이 메일은 무시하셔도 됩니다. 비밀번호는 바뀌지 않아요.
				""".formatted(name, link));
	}

	public EmailMessage emailChangeConfirmation(String newEmail, String name, String token) {
		String link = appProperties.tokenLink("/verify-email-change", token);
		return new EmailMessage(newEmail, SUBJECT_PREFIX + "새 이메일 주소를 확인해 주세요", """
				안녕하세요, %s님.

				RentKeeper 로그인 이메일을 이 주소로 바꾸려고 하셨어요.
				아래 링크를 누른 뒤, 열린 화면에서 [이메일 바꾸기 완료] 버튼을 눌러 주세요.
				버튼을 누르기 전까지는 예전 이메일로 로그인해요.

				%s

				이 링크는 24시간 동안 쓸 수 있어요.
				본인이 요청하지 않았다면 이 메일은 무시하셔도 됩니다.
				""".formatted(name, link));
	}

	/** 이전 주소로 보내는 보안 안내. 이전 주소가 인증돼 있었을 때만 보낸다 */
	public EmailMessage emailChanged(String previousEmail, String name, String newEmail) {
		return new EmailMessage(previousEmail, SUBJECT_PREFIX + "로그인 이메일이 바뀌었어요", """
				안녕하세요, %s님.

				RentKeeper 로그인 이메일이 %s(으)로 바뀌었어요.
				앞으로는 새 이메일로 로그인하고 알림도 새 이메일로 받아요.

				본인이 바꾼 것이 아니라면 바로 문의해 주세요.
				""".formatted(name, newEmail));
	}

	/** 보안 안내 메일. 인증된 주소로만 보낸다 (호출하는 쪽이 findVerifiedEmail로 주소를 얻는다) */
	public EmailMessage passwordChanged(String to, String name) {
		return new EmailMessage(to, SUBJECT_PREFIX + "비밀번호가 변경됐어요", """
				안녕하세요, %s님.

				방금 RentKeeper 비밀번호가 변경됐어요. 안전을 위해 모든 기기에서 로그아웃됐어요.

				본인이 바꾼 것이 아니라면, 로그인 화면의 [비밀번호가 기억나지 않나요?]에서
				바로 비밀번호를 다시 정해 주세요.
				""".formatted(name));
	}

}
