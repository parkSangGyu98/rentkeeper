package com.rentkeeper.auth.kakao;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 카카오 가입 단계 입력. 알림 이메일은 선택이다 (비우면 "나중에 하기").
 */
public record KakaoSignupForm(
		@NotBlank(message = "이름(호칭)을 입력해 주세요") @Size(max = 50, message = "이름은 50자 이하로 입력해 주세요") String name,
		@Size(max = 254, message = "이메일이 너무 길어요") String notificationEmail,
		@AssertTrue(message = "이용약관과 개인정보처리방침에 동의해 주세요") boolean agreeTerms) {

	static KakaoSignupForm prefilled(KakaoProfile profile) {
		return new KakaoSignupForm(profile.nickname() == null ? "" : profile.nickname(),
				profile.email() == null ? "" : profile.email(), false);
	}

}
