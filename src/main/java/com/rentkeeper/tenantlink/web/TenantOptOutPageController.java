package com.rentkeeper.tenantlink.web;

import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

import com.rentkeeper.common.config.AppProperties;
import com.rentkeeper.common.ratelimit.RateLimitRule;
import com.rentkeeper.common.ratelimit.RateLimiter;
import com.rentkeeper.lease.application.TenantAccessService;
import com.rentkeeper.lease.application.TenantAccessService.TenantAccess;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 세입자 안내 문자 그만 받기·다시 받기 (TEN-6, M5 결정 9). 확인 화면을 거쳐 POST로 바꾸고, 결과 화면은 리다이렉트하지
 * 않고 바로 그린다(세션·flash를 쓰지 않는다). 이미 그 상태여도 같은 결과 화면이다(멱등).
 * <p>
 * 수신 거부는 링크가 아니라 세입자에 저장한다. 쓸 수 없는 링크는 다른 세입자 화면과 같은 404다.
 */
@Controller
public class TenantOptOutPageController {

	private final TenantAccessService tenantAccessService;

	private final RateLimiter rateLimiter;

	private final AppProperties appProperties;

	public TenantOptOutPageController(TenantAccessService tenantAccessService, RateLimiter rateLimiter,
			AppProperties appProperties) {
		this.tenantAccessService = tenantAccessService;
		this.rateLimiter = rateLimiter;
		this.appProperties = appProperties;
	}

	/** "안내 문자를 그만 받을까요?" 확인 화면. 이미 거부했으면 [다시 받기]가 있는 결과 화면 */
	@GetMapping("/t/{token}/optout")
	public String confirm(@PathVariable String token, Model model, HttpServletResponse response) {
		Optional<TenantAccess> access = tenantAccessService.open(token);
		if (access.isEmpty()) {
			return TenantPageController.invalid(response);
		}
		model.addAttribute("token", token);
		if (tenantAccessService.notificationsOptedOut(access.get())) {
			model.addAttribute("optedOut", true);
			return "tenant/optout-done";
		}
		return "tenant/optout-confirm";
	}

	@PostMapping("/t/{token}/optout")
	public String optOut(@PathVariable String token, Model model, HttpServletRequest request,
			HttpServletResponse response) {
		return change(token, true, model, request, response);
	}

	@PostMapping("/t/{token}/optin")
	public String optIn(@PathVariable String token, Model model, HttpServletRequest request,
			HttpServletResponse response) {
		return change(token, false, model, request, response);
	}

	private String change(String token, boolean optOut, Model model, HttpServletRequest request,
			HttpServletResponse response) {
		if (!TenantOrigin.allowed(request, appProperties)) {
			response.setStatus(HttpStatus.FORBIDDEN.value());
			return "tenant/forbidden";
		}
		Optional<TenantAccess> access = tenantAccessService.open(token);
		if (access.isEmpty()) {
			return TenantPageController.invalid(response);
		}
		rateLimiter.acquireOrThrow(RateLimitRule.TENANT_OPTOUT_PER_LINK, String.valueOf(access.get().tokenId()));
		if (optOut) {
			tenantAccessService.optOut(access.get());
		} else {
			tenantAccessService.optIn(access.get());
		}
		model.addAttribute("token", token);
		model.addAttribute("optedOut", optOut);
		return "tenant/optout-done";
	}

}
