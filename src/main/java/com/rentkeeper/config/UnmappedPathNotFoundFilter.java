package com.rentkeeper.config;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.springframework.http.HttpStatus;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.ServletRequestPathUtils;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 허용 목록 밖의 주소 중 처리할 컨트롤러가 없는 주소는 권한 검사 전에 404(error/404, "페이지를 찾을 수 없어요")로 끝낸다.
 * <p>
 * 기본 거부(anyRequest().denyAll())는 그대로다. 이 필터는 거부할 요청의 응답만 바꾼다: 없는 주소에 로그인 상태면
 * 403("요청을 처리할 수 없어요"), 비로그인이면 로그인 화면이 나오던 것을 404로. 컨트롤러가 있는 주소는 지금처럼 권한
 * 검사(403·로그인 화면)를 거친다. 허용 목록 안(공개 경로·/app/**·/signup/kakao)은 손대지 않는다: /app/ 아래의
 * 없는 주소는 로그인 뒤 404, 남의 ID는 컨트롤러가 404(IDOR, 존재 여부를 드러내지 않음).
 * <p>
 * 컨트롤러가 있는지 알 수 없으면(예외) 404로 바꾸지 않고 원래대로 막는다.
 */
class UnmappedPathNotFoundFilter extends OncePerRequestFilter {

	private final RequestMatcher allowList;

	/** 빈 순서 문제를 피하려고 처음 요청 때 가져온다 */
	private final Supplier<RequestMappingHandlerMapping> handlerMapping;

	UnmappedPathNotFoundFilter(List<String> allowListPatterns, Supplier<RequestMappingHandlerMapping> handlerMapping) {
		List<RequestMatcher> matchers = new ArrayList<>();
		for (String pattern : allowListPatterns) {
			matchers.add(PathPatternRequestMatcher.withDefaults().matcher(pattern));
		}
		this.allowList = new OrRequestMatcher(matchers);
		this.handlerMapping = handlerMapping;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if (!allowList.matches(request) && !hasController(request)) {
			response.sendError(HttpStatus.NOT_FOUND.value());
			return;
		}
		chain.doFilter(request, response);
	}

	private boolean hasController(HttpServletRequest request) {
		Object previous = request.getAttribute(ServletRequestPathUtils.PATH_ATTRIBUTE);
		ServletRequestPathUtils.parseAndCache(request);
		try {
			return handlerMapping.get().getHandler(request) != null;
		} catch (Exception e) {
			// 주소는 있는데 방식(GET·POST)이 다른 경우 등: 원래대로 권한 검사에 맡긴다
			return true;
		} finally {
			if (previous == null) {
				request.removeAttribute(ServletRequestPathUtils.PATH_ATTRIBUTE);
			} else {
				request.setAttribute(ServletRequestPathUtils.PATH_ATTRIBUTE, previous);
			}
		}
	}

}
