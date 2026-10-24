package com.rentkeeper.tenantlink.web;

import com.rentkeeper.common.config.AppProperties;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 세입자 POST의 출처 확인 (M4 결정 8). /t/** 는 세션·CSRF 토큰을 쓰지 않으므로, 다른 사이트의 폼이 세입자 브라우저로
 * 대신 보내는 것을 Origin 헤더로 막는다. Origin이 있으면 app.base-url의 출처와 같아야 한다. 없으면(오래된 브라우저)
 * 받는다. "null"은 허용하지 않는다.
 */
final class TenantOrigin {

	private TenantOrigin() {
	}

	static boolean allowed(HttpServletRequest request, AppProperties appProperties) {
		String origin = request.getHeader("Origin");
		return origin == null || origin.equalsIgnoreCase(appProperties.origin());
	}

}
