package com.rentkeeper.tenantlink.web;

import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

import com.rentkeeper.billing.application.ChargeQueryService;
import com.rentkeeper.lease.application.TenantAccessService;
import com.rentkeeper.lease.application.TenantAccessService.TenantAccess;

import jakarta.servlet.http.HttpServletResponse;

/**
 * 세입자 링크 화면 (TEN-1·2·4, M4 결정 5·6). 가입 없이 링크(/t/{토큰})로만 들어온다. 모든 조회는 토큰이 가리키는
 * 계약 하나로 한정한다(TenantAccess).
 * <p>
 * 형식이 틀린 토큰, 없는 토큰, 닫힌 링크, 기간이 지난 링크, 없는 경로는 모두 같은 404 화면이다. 요청 수는
 * {@link TenantLinkRateLimitInterceptor}가 먼저 센다.
 */
@Controller
public class TenantPageController {

	static final String INVALID_VIEW = "tenant/invalid";

	private final TenantAccessService tenantAccessService;

	private final ChargeQueryService chargeQueryService;

	public TenantPageController(TenantAccessService tenantAccessService, ChargeQueryService chargeQueryService) {
		this.tenantAccessService = tenantAccessService;
		this.chargeQueryService = chargeQueryService;
	}

	/** 보낼 월세와 입금 계좌 */
	@GetMapping("/t/{token}")
	public String home(@PathVariable String token, Model model, HttpServletResponse response) {
		Optional<TenantAccess> access = tenantAccessService.open(token);
		if (access.isEmpty()) {
			return invalid(response);
		}
		TenantAccess a = access.get();
		model.addAttribute("token", token);
		model.addAttribute("lease", tenantAccessService.lease(a));
		model.addAttribute("charges", chargeQueryService.tenantDue(a.landlordId(), a.leaseId()));
		model.addAttribute("thisMonth", chargeQueryService.thisMonth());
		model.addAttribute("optedOut", tenantAccessService.notificationsOptedOut(a));
		return "tenant/home";
	}

	/** 지난 기록 (최근 12개월, TEN-4) */
	@GetMapping("/t/{token}/history")
	public String history(@PathVariable String token, Model model, HttpServletResponse response) {
		Optional<TenantAccess> access = tenantAccessService.open(token);
		if (access.isEmpty()) {
			return invalid(response);
		}
		TenantAccess a = access.get();
		model.addAttribute("token", token);
		model.addAttribute("lease", tenantAccessService.lease(a));
		model.addAttribute("charges", chargeQueryService.tenantHistory(a.landlordId(), a.leaseId()));
		return "tenant/history";
	}

	/** /t/ 아래의 다른 모든 주소 (없는 경로도 같은 404, 요청 수도 함께 센다) */
	@RequestMapping("/t/**")
	public String unknown(HttpServletResponse response) {
		return invalid(response);
	}

	static String invalid(HttpServletResponse response) {
		response.setStatus(HttpStatus.NOT_FOUND.value());
		return INVALID_VIEW;
	}

}
