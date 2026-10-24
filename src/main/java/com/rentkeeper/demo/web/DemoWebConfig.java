package com.rentkeeper.demo.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 데모 계정 제한을 계정 설정 주소에만 붙인다 */
@Configuration(proxyBeanMethods = false)
class DemoWebConfig implements WebMvcConfigurer {

	private final DemoRestrictionInterceptor restriction;

	DemoWebConfig(DemoRestrictionInterceptor restriction) {
		this.restriction = restriction;
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(restriction).addPathPatterns(DemoRestrictionInterceptor.PATHS);
	}

}
