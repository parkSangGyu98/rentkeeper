package com.rentkeeper.demo.web;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PostMapping;

import com.rentkeeper.auth.security.LandlordPrincipal;
import com.rentkeeper.auth.web.AuthenticatedSessionHelper;
import com.rentkeeper.common.config.AppProperties;
import com.rentkeeper.common.error.ResourceNotFoundException;
import com.rentkeeper.common.ratelimit.RateLimitRule;
import com.rentkeeper.common.ratelimit.RateLimiter;
import com.rentkeeper.common.web.ClientIp;
import com.rentkeeper.demo.application.DemoService;
import com.rentkeeper.demo.application.DemoService.DemoStart;
import com.rentkeeper.landlord.application.LandlordService;
import com.rentkeeper.landlord.application.LandlordService.DemoCapacityException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * [데모로 둘러보기] (M6 결정 3). 방문자마다 임시 데모 계정을 만들고 바로 로그인한다. 시작 화면은 리다이렉트하지 않고 바로 그린다:
 * 세입자 링크 원문이 들어 있어 flash·세션에 넣지 않는다(M4 결정 2와 같은 이유).
 * <ul>
 * <li>설정(app.demo.enabled)이 꺼져 있으면 404</li>
 * <li>IP당 1시간 5회, 동시에 있는 데모 계정 수 상한</li>
 * <li>이미 로그인했으면 새로 만들지 않는다</li>
 * </ul>
 */
@Controller
public class DemoController {

	private final DemoService demoService;

	private final AuthenticatedSessionHelper sessionHelper;

	private final RateLimiter rateLimiter;

	private final AppProperties appProperties;

	private final Clock clock;

	public DemoController(DemoService demoService, AuthenticatedSessionHelper sessionHelper, RateLimiter rateLimiter,
			AppProperties appProperties, Clock clock) {
		this.demoService = demoService;
		this.sessionHelper = sessionHelper;
		this.rateLimiter = rateLimiter;
		this.appProperties = appProperties;
		this.clock = clock;
	}

	@PostMapping("/demo")
	public String start(Authentication authentication, Model model, HttpServletRequest request,
			HttpServletResponse response) {
		if (!demoService.enabled()) {
			throw new ResourceNotFoundException("demo");
		}
		if (authentication != null && authentication.getPrincipal() instanceof LandlordPrincipal) {
			return "redirect:/app";
		}
		rateLimiter.acquireOrThrow(RateLimitRule.DEMO_START_PER_IP, ClientIp.of(request));
		DemoStart demo;
		try {
			demo = demoService.start();
		} catch (DemoCapacityException e) {
			response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
			return "demo/full";
		}
		sessionHelper.login(
				new LandlordPrincipal(demo.landlordId(), demo.email(), LandlordService.DEMO_NAME, true, null), request,
				response);
		// 세입자 링크 원문이 든 화면: 브라우저·프록시가 저장하지 않게 한다
		response.setHeader("Cache-Control", "no-store");
		model.addAttribute("tenantLink", appProperties.tenantLink(demo.tenantLinkToken()));
		model.addAttribute("demoExpiresAt", LocalDateTime.ofInstant(demo.expiresAt(), clock.getZone()));
		return "demo/welcome";
	}

}
