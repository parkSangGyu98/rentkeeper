package com.rentkeeper.auth.emailchange;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import com.rentkeeper.auth.settings.AccountSettingsController;

/**
 * 계정 설정 화면에 "이메일 바꾸기" 버튼을 켠다. 이메일 바꾸기 기능(M1-b 8b)을 빼면 이 클래스도 함께 빠져 버튼이 사라진다.
 */
@ControllerAdvice(assignableTypes = AccountSettingsController.class)
public class EmailChangeSettingsAdvice {

	@ModelAttribute("emailChangeEnabled")
	public boolean emailChangeEnabled() {
		return true;
	}

}
