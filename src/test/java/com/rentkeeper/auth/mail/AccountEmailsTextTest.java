package com.rentkeeper.auth.mail;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;

import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;

import com.rentkeeper.common.config.AppProperties;
import com.rentkeeper.common.mail.EmailMessage;

/**
 * 계정 메일 6종의 제목·본문 글자를 고정한다 (디자인 교체 결정 3). 서비스명을 messages의 app.name 한 곳에서 읽도록
 * 바꿔도 받는 사람이 보는 글자는 그대로여야 한다.
 */
class AccountEmailsTextTest {

	private final AccountEmails emails = new AccountEmails(new AppProperties(URI.create("https://rk.example"), null),
			messages());

	@Test
	void verificationMail() {
		EmailMessage mail = emails.emailVerification("kim@example.com", "김임대", "TOKEN");

		assertThat(mail.subject()).isEqualTo("[RentKeeper] 이메일 인증을 완료해 주세요");
		assertThat(mail.body()).isEqualTo("""
				안녕하세요, 김임대님.

				RentKeeper에 가입해 주셔서 감사합니다.
				아래 링크를 누른 뒤, 열린 화면에서 [인증 완료하기] 버튼을 눌러 주세요.

				https://rk.example/verify-email?token=TOKEN

				이 링크는 24시간 동안 쓸 수 있어요.
				본인이 가입하지 않았다면 이 메일은 무시하셔도 됩니다.
				""");
	}

	@Test
	void passwordResetMail() {
		EmailMessage mail = emails.passwordReset("kim@example.com", "김임대", "TOKEN");

		assertThat(mail.subject()).isEqualTo("[RentKeeper] 비밀번호 재설정 안내");
		assertThat(mail.body()).isEqualTo("""
				안녕하세요, 김임대님.

				비밀번호 재설정을 요청하셨어요.
				아래 링크를 눌러 새 비밀번호를 정해 주세요.

				https://rk.example/password-reset/confirm?token=TOKEN

				이 링크는 30분 동안, 한 번만 쓸 수 있어요.
				본인이 요청하지 않았다면 이 메일은 무시하셔도 됩니다. 비밀번호는 바뀌지 않아요.
				""");
	}

	@Test
	void emailChangeConfirmationMail() {
		EmailMessage mail = emails.emailChangeConfirmation("new@example.com", "김임대", "TOKEN");

		assertThat(mail.subject()).isEqualTo("[RentKeeper] 새 이메일 주소를 확인해 주세요");
		assertThat(mail.body()).isEqualTo("""
				안녕하세요, 김임대님.

				RentKeeper 로그인 이메일을 이 주소로 바꾸려고 하셨어요.
				아래 링크를 누른 뒤, 열린 화면에서 [이메일 바꾸기 완료] 버튼을 눌러 주세요.
				버튼을 누르기 전까지는 예전 이메일로 로그인해요.

				https://rk.example/verify-email-change?token=TOKEN

				이 링크는 24시간 동안 쓸 수 있어요.
				본인이 요청하지 않았다면 이 메일은 무시하셔도 됩니다.
				""");
	}

	@Test
	void emailChangedMail() {
		EmailMessage mail = emails.emailChanged("old@example.com", "김임대", "new@example.com");

		assertThat(mail.subject()).isEqualTo("[RentKeeper] 로그인 이메일이 바뀌었어요");
		assertThat(mail.body()).isEqualTo("""
				안녕하세요, 김임대님.

				RentKeeper 로그인 이메일이 new@example.com(으)로 바뀌었어요.
				앞으로는 새 이메일로 로그인하고 알림도 새 이메일로 받아요.

				본인이 바꾼 것이 아니라면 바로 문의해 주세요.
				""");
	}

	@Test
	void withdrawnMail() {
		EmailMessage mail = emails.withdrawn("kim@example.com", "김임대");

		assertThat(mail.subject()).isEqualTo("[RentKeeper] 탈퇴 처리됐어요");
		assertThat(mail.body()).isEqualTo("""
				안녕하세요, 김임대님.

				RentKeeper 회원 탈퇴가 처리됐어요. 모든 기기에서 로그아웃됐고, 더 이상 로그인할 수 없어요.
				계정 정보는 30일 뒤에 완전히 지워요. 탈퇴를 되돌리는 기능은 없어요.
				다시 이용하시려면 언제든 새로 가입하실 수 있어요.

				본인이 탈퇴한 것이 아니라면 바로 문의해 주세요.
				""");
	}

	@Test
	void passwordChangedMail() {
		EmailMessage mail = emails.passwordChanged("kim@example.com", "김임대");

		assertThat(mail.subject()).isEqualTo("[RentKeeper] 비밀번호가 변경됐어요");
		assertThat(mail.body()).isEqualTo("""
				안녕하세요, 김임대님.

				방금 RentKeeper 비밀번호가 변경됐어요. 안전을 위해 모든 기기에서 로그아웃됐어요.

				본인이 바꾼 것이 아니라면, 로그인 화면의 [비밀번호가 기억나지 않나요?]에서
				바로 비밀번호를 다시 정해 주세요.
				""");
	}

	/** 앱과 같은 messages 파일을 읽는다 */
	private static ResourceBundleMessageSource messages() {
		ResourceBundleMessageSource source = new ResourceBundleMessageSource();
		source.setBasename("messages");
		source.setDefaultEncoding("UTF-8");
		return source;
	}

}
