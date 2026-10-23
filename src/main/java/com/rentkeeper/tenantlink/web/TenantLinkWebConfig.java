package com.rentkeeper.tenantlink.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 세입자 링크 화면의 요청 수 세기 등록 (/t/** 에만) */
@Configuration(proxyBeanMethods = false)
class TenantLinkWebConfig implements WebMvcConfigurer {

	private final TenantLinkRateLimitInterceptor rateLimitInterceptor;

	TenantLinkWebConfig(TenantLinkRateLimitInterceptor rateLimitInterceptor) {
		this.rateLimitInterceptor = rateLimitInterceptor;
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(rateLimitInterceptor).addPathPatterns("/t/**");
	}

}
