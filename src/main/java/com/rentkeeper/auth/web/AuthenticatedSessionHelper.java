package com.rentkeeper.auth.web;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;

import com.rentkeeper.auth.rememberme.HashingRememberMeServices;
import com.rentkeeper.auth.security.LandlordPrincipal;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 가입 직후 바로 로그인 상태로 만든다. 로그인 폼(POST /login)을 거치지 않으므로 Spring Security가 자동으로 해 주는
 * 일을 여기서 직접 한다: 세션 id 교체(세션 고정 공격 방지), 로그인 정보를 세션에 저장. 탈퇴 직후의 로그아웃도 맡는다.
 */
@Component
public class AuthenticatedSessionHelper {

	private final SecurityContextHolderStrategy contextHolderStrategy = SecurityContextHolder
			.getContextHolderStrategy();

	// Spring Security 6 이후 기본값과 같은 저장 방식 (요청 속성 + 세션)
	private final SecurityContextRepository contextRepository = new DelegatingSecurityContextRepository(
			new RequestAttributeSecurityContextRepository(), new HttpSessionSecurityContextRepository());

	public void login(LandlordPrincipal principal, HttpServletRequest request, HttpServletResponse response) {
		if (request.getSession(false) != null) {
			request.changeSessionId();
		}
		SecurityContext context = contextHolderStrategy.createEmptyContext();
		context.setAuthentication(
				UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
		contextHolderStrategy.setContext(context);
		contextRepository.saveContext(context, request, response);
	}

	/**
	 * 지금 기기에서 로그아웃한다 (탈퇴 직후 등): 세션을 없애고 로그인 유지 쿠키도 지운다. DB의 remember-me 토큰은 호출한 쪽이
	 * 지운다 (AccountSessionTerminator).
	 */
	public void logout(HttpServletRequest request, HttpServletResponse response) {
		new SecurityContextLogoutHandler().logout(request, response,
				contextHolderStrategy.getContext().getAuthentication());
		Cookie cookie = new Cookie(HashingRememberMeServices.COOKIE_NAME, "");
		cookie.setMaxAge(0);
		cookie.setPath(request.getContextPath().isEmpty() ? "/" : request.getContextPath());
		cookie.setSecure(true);
		cookie.setHttpOnly(true);
		response.addCookie(cookie);
	}

}
