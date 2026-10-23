package com.rentkeeper.tenantlink.web;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import com.rentkeeper.common.ratelimit.RateLimitRule;
import com.rentkeeper.common.ratelimit.RateLimiter;
import com.rentkeeper.common.web.ClientIp;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * /t/** 의 모든 요청을 IP당 1분에 30회로 센다 (M4 결정 9, ADR-012). 토큰을 확인하기 전에 센다: 형식이 틀리거나
 * 없는 토큰도 센다. 넘으면 {@link TenantPageAdvice}가 세입자용 429 화면을 보인다.
 * <p>
 * 한계: ClientIp는 아직 getRemoteAddr()라 프록시 뒤에서는 모든 세입자가 이 한도를 나눠 쓴다("출시 전 필수",
 * M6). 대입 공격의 실제 방어는 256비트 토큰이다.
 */
@Component
class TenantLinkRateLimitInterceptor implements HandlerInterceptor {

	private final RateLimiter rateLimiter;

	TenantLinkRateLimitInterceptor(RateLimiter rateLimiter) {
		this.rateLimiter = rateLimiter;
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		rateLimiter.acquireOrThrow(RateLimitRule.TENANT_LINK_PER_IP, ClientIp.of(request));
		return true;
	}

}
