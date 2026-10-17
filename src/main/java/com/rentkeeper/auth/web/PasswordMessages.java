package com.rentkeeper.auth.web;

import com.rentkeeper.auth.signup.PasswordPolicy;

/**
 * 비밀번호 규칙 위반 안내 문구. 가입·재설정·비밀번호 변경 화면이 같은 문구를 쓴다.
 */
public final class PasswordMessages {

	private PasswordMessages() {
	}

	public static String forViolation(PasswordPolicy.Violation violation) {
		return switch (violation) {
			case TOO_SHORT -> "비밀번호는 8자 이상이어야 해요";
			case TOO_LONG -> "비밀번호가 너무 길어요 (영문·숫자 72자, 한글 24자까지)";
			case TOO_COMMON -> "너무 흔한 비밀번호예요. 다른 사람이 쉽게 짐작하기 어려운 비밀번호로 정해 주세요";
			case SAME_AS_EMAIL -> "이메일과 같은 비밀번호는 쓸 수 없어요";
			case REPEATED_CHARACTER -> "같은 글자만 반복한 비밀번호는 쓸 수 없어요";
		};
	}

}
