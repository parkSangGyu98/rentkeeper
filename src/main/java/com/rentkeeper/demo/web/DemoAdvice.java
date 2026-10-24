package com.rentkeeper.demo.web;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import com.rentkeeper.auth.security.LandlordPrincipal;
import com.rentkeeper.demo.application.DemoService;

/**
 * 모든 화면에 데모 정보를 넣는다 (M6 결정 3): 첫 화면의 [데모로 둘러보기]를 보일지, 로그인한 계정이 데모면 지워질 시각(레이아웃의
 * 데모 띠). 로그인하지 않았으면 DB를 보지 않는다.
 */
@ControllerAdvice
class DemoAdvice {

	private final DemoService demoService;

	private final Clock clock;

	DemoAdvice(DemoService demoService, Clock clock) {
		this.demoService = demoService;
		this.clock = clock;
	}

	@ModelAttribute("demoEnabled")
	boolean demoEnabled() {
		return demoService.enabled();
	}

	/** 데모 계정이 아니면 null */
	@ModelAttribute("demoExpiresAt")
	LocalDateTime demoExpiresAt(Authentication authentication) {
		if (authentication == null || !(authentication.getPrincipal() instanceof LandlordPrincipal principal)) {
			return null;
		}
		return demoService.demoExpiresAt(principal.getLandlordId())
				.map(at -> LocalDateTime.ofInstant(at, clock.getZone())).orElse(null);
	}

}
