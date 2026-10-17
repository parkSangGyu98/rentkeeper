package com.rentkeeper.auth.web;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 로그인 화면. 로그인 처리(POST /login)와 로그아웃(POST /logout)은 Spring Security가 맡는다
 * (SecurityConfig).
 */
@Controller
public class LoginController {

	@GetMapping("/login")
	public String loginForm(Authentication authentication) {
		// 이미 로그인한 사람이 로그인 화면에 오면 바로 내 화면으로 보낸다
		if (authentication != null && authentication.isAuthenticated()
				&& !(authentication instanceof AnonymousAuthenticationToken)) {
			return "redirect:/app";
		}
		return "auth/login";
	}

}
