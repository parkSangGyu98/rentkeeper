package com.rentkeeper.auth.signup;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 가입 폼 입력. 형식 검사(빈 칸, 길이, 동의)만 여기서 하고, 이메일 형식·비밀번호 규칙·중복 확인은 SignupService가 한다.
 * <p>
 * record의 기본 toString()은 모든 값을 출력하므로 비밀번호가 로그에 남을 수 있다 → 직접 재정의해 감춘다.
 */
public record SignupForm(@NotBlank(message = "이메일을 입력해 주세요") @Size(max = 254, message = "이메일이 너무 길어요") String email,
		@NotBlank(message = "비밀번호를 입력해 주세요") String password,
		@NotBlank(message = "비밀번호를 한 번 더 입력해 주세요") String passwordConfirm,
		@NotBlank(message = "이름(호칭)을 입력해 주세요") @Size(max = 50, message = "이름은 50자 이하로 입력해 주세요") String name,
		@AssertTrue(message = "이용약관과 개인정보처리방침에 동의해 주세요") boolean agreeTerms) {

	public static SignupForm empty() {
		return new SignupForm("", "", "", "", false);
	}

	@Override
	public String toString() {
		return "SignupForm[email=" + email + ", name=" + name + ", agreeTerms=" + agreeTerms + ", password=***]";
	}

}
