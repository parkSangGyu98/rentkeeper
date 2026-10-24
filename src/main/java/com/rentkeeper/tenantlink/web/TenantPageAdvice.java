package com.rentkeeper.tenantlink.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import com.rentkeeper.common.ratelimit.RateLimitExceededException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 세입자 링크 화면의 오류 화면. 임대인 화면의 오류 페이지(로그인 버튼이 있는 레이아웃) 대신 세입자용 화면을 보인다.
 * <p>
 * 로그에는 토큰을 가린 주소와 예외 종류만 남긴다(M4 추가 요구). 예외 메시지·스택에 경로 값이 섞일 수 있어 그대로 넘기지 않는다.
 */
@ControllerAdvice(basePackageClasses = TenantPageAdvice.class)
class TenantPageAdvice {

	private static final Logger log = LoggerFactory.getLogger(TenantPageAdvice.class);

	@ExceptionHandler(RateLimitExceededException.class)
	String tooManyRequests(HttpServletResponse response) {
		response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
		return "tenant/too-many";
	}

	@ExceptionHandler(Exception.class)
	String failed(Exception e, HttpServletRequest request, HttpServletResponse response) {
		log.error("tenant page failed: {} {} ({}: {})", request.getMethod(), TenantPaths.mask(request.getRequestURI()),
				e.getClass().getName(), TenantPaths.mask(e.getMessage()));
		response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
		return "tenant/error";
	}

}
