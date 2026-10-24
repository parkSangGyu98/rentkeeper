package com.rentkeeper.demo.web;

import java.util.List;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.FlashMap;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.support.RequestContextUtils;

import com.rentkeeper.auth.security.LandlordPrincipal;
import com.rentkeeper.demo.application.DemoService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 데모 계정이 할 수 없는 일 (M6 결정 3 보강): 계정 설정 바꾸기(비밀번호·이메일·알림 받을 이메일·탈퇴)와 인증 메일 다시 받기.
 * 외부 메일 주소를 넣거나 실제 메일이 나가는 일을 막는다. 화면을 열거나 보내면 설정 화면으로 돌려보내고 안내한다. 세입자 안내 문자
 * 켜기는 알림 설정 화면이 막는다.
 */
@Component
class DemoRestrictionInterceptor implements HandlerInterceptor {

	/** 막는 주소 (DemoWebConfig가 이 주소에만 붙인다) */
	static final List<String> PATHS = List.of("/app/settings/password", "/app/settings/email",
			"/app/settings/notification-email", "/app/settings/withdraw", "/app/settings/withdraw/**",
			"/app/email-verification/resend");

	static final String NOTICE = "데모 계정에서는 계정 설정을 바꿀 수 없어요. 가입하면 쓸 수 있어요.";

	private final DemoService demoService;

	DemoRestrictionInterceptor(DemoService demoService) {
		this.demoService = demoService;
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
			throws Exception {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !(authentication.getPrincipal() instanceof LandlordPrincipal principal)
				|| demoService.demoExpiresAt(principal.getLandlordId()).isEmpty()) {
			return true;
		}
		FlashMap flash = RequestContextUtils.getOutputFlashMap(request);
		flash.put("notice", NOTICE);
		RequestContextUtils.saveOutputFlashMap("/app/settings", request, response);
		response.sendRedirect(request.getContextPath() + "/app/settings");
		return false;
	}

}
