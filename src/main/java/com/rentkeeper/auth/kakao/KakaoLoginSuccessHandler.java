package com.rentkeeper.auth.kakao;

import java.io.IOException;

import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 카카오 로그인 성공 후 이동: 기존 회원은 내 화면, 처음 온 사람은 가입 단계.
 */
@Component
public class KakaoLoginSuccessHandler implements AuthenticationSuccessHandler {

	@Override
	public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
			Authentication authentication) throws IOException {
		String target = authentication.getPrincipal() instanceof KakaoSignupPrincipal ? "/signup/kakao" : "/app";
		response.sendRedirect(request.getContextPath() + target);
	}

}
