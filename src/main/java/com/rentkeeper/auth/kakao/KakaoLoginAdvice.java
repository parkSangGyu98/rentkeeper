package com.rentkeeper.auth.kakao;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * 화면에 "카카오로 시작하기" 버튼을 보일지. 카카오 키가 없는 local·CI에서는 버튼을 숨긴다 (M1-c 결정 10).
 */
@ControllerAdvice
public class KakaoLoginAdvice {

	private final KakaoProperties properties;

	public KakaoLoginAdvice(KakaoProperties properties) {
		this.properties = properties;
	}

	@ModelAttribute("kakaoLoginEnabled")
	public boolean kakaoLoginEnabled() {
		return properties.enabled();
	}

}
