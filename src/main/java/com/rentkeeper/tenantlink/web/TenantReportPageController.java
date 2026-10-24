package com.rentkeeper.tenantlink.web;

import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

import com.rentkeeper.billing.application.ChargeQueryService;
import com.rentkeeper.billing.application.TenantChargeViews.TenantCharge;
import com.rentkeeper.billing.application.TenantReportService;
import com.rentkeeper.billing.application.TenantReportService.ReportOutcome;
import com.rentkeeper.common.config.AppProperties;
import com.rentkeeper.common.error.ResourceNotFoundException;
import com.rentkeeper.common.ratelimit.RateLimitRule;
import com.rentkeeper.common.ratelimit.RateLimiter;
import com.rentkeeper.lease.application.TenantAccessService;
import com.rentkeeper.lease.application.TenantAccessService.TenantAccess;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 세입자의 "입금했어요" (TEN-3, M4 결정 7·8·9). 확인 화면을 거쳐 POST로 알린다. 결과 화면은 리다이렉트하지 않고 바로
 * 그린다(세션·flash를 쓰지 않는다). 새로고침으로 다시 와도 이미 알린 상태라 바뀌지 않는다(멱등).
 * <p>
 * 청구는 링크가 가리키는 계약의 것만. 다른 계약·다른 임대인의 청구 ID, 취소한 청구는 틀린 링크와 같은 404다.
 */
@Controller
public class TenantReportPageController {

	private final TenantAccessService tenantAccessService;

	private final ChargeQueryService chargeQueryService;

	private final TenantReportService reportService;

	private final RateLimiter rateLimiter;

	private final AppProperties appProperties;

	public TenantReportPageController(TenantAccessService tenantAccessService, ChargeQueryService chargeQueryService,
			TenantReportService reportService, RateLimiter rateLimiter, AppProperties appProperties) {
		this.tenantAccessService = tenantAccessService;
		this.chargeQueryService = chargeQueryService;
		this.reportService = reportService;
		this.rateLimiter = rateLimiter;
		this.appProperties = appProperties;
	}

	/** "○월 월세 ○원을 보내셨나요?" 확인 화면 */
	@GetMapping("/t/{token}/charges/{chargeId}/report-paid")
	public String confirm(@PathVariable String token, @PathVariable String chargeId, Model model,
			HttpServletResponse response) {
		Optional<TenantAccess> access = tenantAccessService.open(token);
		Optional<TenantCharge> charge = access.flatMap(a -> charge(a, chargeId));
		if (charge.isEmpty()) {
			return TenantPageController.invalid(response);
		}
		model.addAttribute("token", token);
		model.addAttribute("charge", charge.get());
		model.addAttribute("thisMonth", chargeQueryService.thisMonth());
		if (charge.get().reported() || charge.get().paid()) {
			model.addAttribute("outcome",
					charge.get().paid() ? ReportOutcome.ALREADY_PAID : ReportOutcome.ALREADY_REPORTED);
			return "tenant/reported";
		}
		return "tenant/report-confirm";
	}

	@PostMapping("/t/{token}/charges/{chargeId}/report-paid")
	public String report(@PathVariable String token, @PathVariable String chargeId, Model model,
			HttpServletRequest request, HttpServletResponse response) {
		// 다른 사이트의 폼이 세입자 브라우저로 대신 보내는 것을 막는다 (M4 결정 8)
		if (!TenantOrigin.allowed(request, appProperties)) {
			response.setStatus(HttpStatus.FORBIDDEN.value());
			return "tenant/forbidden";
		}
		Optional<TenantAccess> access = tenantAccessService.open(token);
		Optional<TenantCharge> charge = access.flatMap(a -> charge(a, chargeId));
		if (charge.isEmpty()) {
			return TenantPageController.invalid(response);
		}
		TenantAccess a = access.get();
		rateLimiter.acquireOrThrow(RateLimitRule.TENANT_REPORT_PER_LINK, String.valueOf(a.tokenId()));
		ReportOutcome outcome;
		try {
			outcome = reportService.report(a.tokenId(), a.landlordId(), a.leaseId(), charge.get().chargeId());
		} catch (ResourceNotFoundException e) {
			// 그 사이 취소됐다
			return TenantPageController.invalid(response);
		}
		model.addAttribute("token", token);
		model.addAttribute("charge", charge.get());
		model.addAttribute("thisMonth", chargeQueryService.thisMonth());
		model.addAttribute("outcome", outcome);
		return "tenant/reported";
	}

	private Optional<TenantCharge> charge(TenantAccess access, String rawChargeId) {
		long chargeId;
		try {
			chargeId = Long.parseLong(rawChargeId);
		} catch (NumberFormatException e) {
			return Optional.empty();
		}
		return chargeQueryService.tenantCharge(access.landlordId(), access.leaseId(), chargeId);
	}

}
