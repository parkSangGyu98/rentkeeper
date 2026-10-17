package com.rentkeeper.auth.web;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import com.rentkeeper.common.ratelimit.RateLimitRule;
import com.rentkeeper.common.ratelimit.RateLimiter;
import com.rentkeeper.common.web.ClientIp;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 로그인 시도(POST /login)를 IP당 10분에 30회로 제한한다 (ADR-012). 한 IP가 여러 계정을 돌아가며 잠그거나
 * 대입하는 것을 막는다.
 * <p>
 * 한도를 넘으면 429 상태와 "요청이 너무 많아요" 화면(error/429)을 보낸다. IP 기준이라 특정 계정에 대한 정보는 드러나지
 * 않는다.
 */
public class LoginRateLimitFilter extends OncePerRequestFilter {

	private final RateLimiter rateLimiter;

	public LoginRateLimitFilter(RateLimiter rateLimiter) {
		this.rateLimiter = rateLimiter;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		// getServletPath()는 서블릿 매핑·테스트 환경에 따라 비어 있을 수 있어 요청 URI로 비교한다
		String path = request.getRequestURI().substring(request.getContextPath().length());
		return !("POST".equals(request.getMethod()) && "/login".equals(path));
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if (!rateLimiter.tryAcquire(RateLimitRule.LOGIN_PER_IP, ClientIp.of(request))) {
			response.sendError(HttpStatus.TOO_MANY_REQUESTS.value());
			return;
		}
		chain.doFilter(request, response);
	}

}
