package com.rentkeeper.billing.web;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.rentkeeper.auth.security.LandlordPrincipal;
import com.rentkeeper.billing.application.ChargeExceptions.ChargeCanceledException;
import com.rentkeeper.billing.application.ChargeExceptions.ChargeChangedException;
import com.rentkeeper.billing.application.ChargeExceptions.NotReportedException;
import com.rentkeeper.billing.application.ChargeExceptions.ReasonInvalidException;
import com.rentkeeper.billing.application.ChargeQueryService;
import com.rentkeeper.billing.application.ChargeViews.ChargeDetail;
import com.rentkeeper.billing.application.TenantReportService;
import com.rentkeeper.billing.domain.ChargeStatus;

/**
 * 세입자 "입금했어요" 신고 반려 (M4 결정 10). 확인은 입금 기록 화면(?report=true)으로 한다(결정 11). 남의 청구면
 * 404.
 */
@Controller
public class TenantReportController {

	private final TenantReportService reportService;

	private final ChargeQueryService queryService;

	public TenantReportController(TenantReportService reportService, ChargeQueryService queryService) {
		this.reportService = reportService;
		this.queryService = queryService;
	}

	@GetMapping("/app/charges/{id}/report/reject")
	public String rejectForm(@AuthenticationPrincipal LandlordPrincipal principal, @PathVariable long id, Model model,
			RedirectAttributes redirect) {
		ChargeDetail detail = queryService.detail(principal.getLandlordId(), id);
		if (!detail.reported()) {
			return ChargeController.alertDetail(id, redirect, ChargeMessages.NOT_REPORTED);
		}
		model.addAttribute("charge", detail);
		model.addAttribute("version", detail.version());
		model.addAttribute("reason", "");
		return "billing/report-reject";
	}

	@PostMapping("/app/charges/{id}/report/reject")
	public String reject(@AuthenticationPrincipal LandlordPrincipal principal, @PathVariable long id,
			@RequestParam(name = "version", defaultValue = "-1") long version,
			@RequestParam(name = "reason", defaultValue = "") String reason, Model model, RedirectAttributes redirect) {
		long landlordId = principal.getLandlordId();
		ChargeDetail detail = queryService.detail(landlordId, id);
		ChargeStatus status;
		try {
			status = reportService.reject(landlordId, id, version, reason);
		} catch (ReasonInvalidException e) {
			model.addAttribute("charge", detail);
			model.addAttribute("version", version);
			model.addAttribute("reason", reason);
			model.addAttribute("reasonError", ChargeMessages.REJECT_REASON_REQUIRED);
			return "billing/report-reject";
		} catch (NotReportedException e) {
			return ChargeController.alertDetail(id, redirect, ChargeMessages.NOT_REPORTED);
		} catch (ChargeCanceledException e) {
			return ChargeController.alertDetail(id, redirect, ChargeMessages.CANCELED);
		} catch (ChargeChangedException | OptimisticLockingFailureException e) {
			return ChargeController.alertDetail(id, redirect,
					ChargeMessages.changed(queryService.status(landlordId, id)));
		}
		return ChargeController.backToDetail(id, redirect, ChargeMessages.rejected(status));
	}

}
