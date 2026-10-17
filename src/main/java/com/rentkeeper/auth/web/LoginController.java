package com.rentkeeper.auth.web;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import com.rentkeeper.auth.rememberme.HashingRememberMeServices;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

/**
 * 로그인 화면. 로그인 처리(POST /login)와 로그아웃(POST /logout)은 Spring Security가 맡는다
 * (SecurityConfig).
 */
@Controller
public class LoginController {

	@GetMapping("/login")
	public String loginForm(Authentication authentication, HttpServletRequest request, Model model) {
		// 이미 로그인한 사람이 로그인 화면에 오면 바로 내 화면으로 보낸다
		if (authentication != null && authentication.isAuthenticated()
				&& !(authentication instanceof AnonymousAuthenticationToken)) {
			return "redirect:/app";
		}
		// remember-me 토큰 재사용(도난 의심)으로 로그인 유지가 해제된 경우 한 번만 안내한다 (ADR-013)
		HttpSession session = request.getSession(false);
		if (session != null && session.getAttribute(HashingRememberMeServices.THEFT_NOTICE_SESSION_ATTRIBUTE) != null) {
			session.removeAttribute(HashingRememberMeServices.THEFT_NOTICE_SESSION_ATTRIBUTE);
			model.addAttribute("rememberMeReset", true);
		}
		return "auth/login";
	}

}
