package com.rentkeeper.billing.web;

import java.time.YearMonth;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.rentkeeper.auth.security.LandlordPrincipal;
import com.rentkeeper.billing.application.ChargeExceptions.MonthUnavailableException;
import com.rentkeeper.billing.application.ChargeExceptions.ZeroTotalException;
import com.rentkeeper.billing.application.ManualChargeService;
import com.rentkeeper.billing.application.ManualChargeService.NewChargeView;

/**
 * 받을 월세 직접 만들기 (계약 상세의 [이번 달 받을 월세 만들기]·[다른 달 받을 월세 만들기], M3-b 결정 7·8·11). 달을
 * 고르면 그 달 금액이 채워진 확인 화면이 나오고, [만들기]를 누르면 만들어 바로 발행한다. 남의 계약이면 404.
 */
@Controller
public class ManualChargeController {

	private final ManualChargeService manualChargeService;

	public ManualChargeController(ManualChargeService manualChargeService) {
		this.manualChargeService = manualChargeService;
	}

	/**
	 * @param month
	 *            "2026-10". 없으면 달 고르기 화면
	 */
	@GetMapping("/app/leases/{id}/charges/new")
	public String form(@AuthenticationPrincipal LandlordPrincipal principal, @PathVariable long id,
			@RequestParam(name = "month", defaultValue = "") String month, Model model) {
		long landlordId = principal.getLandlordId();
		YearMonth chosen = NewChargeForm.parseMonth(month);
		NewChargeView view;
		try {
			view = manualChargeService.view(landlordId, id, chosen);
		} catch (MonthUnavailableException e) {
			view = manualChargeService.view(landlordId, id, null);
			model.addAttribute("error", ChargeMessages.monthUnavailable(e.reason()));
		}
		model.addAttribute("view", view);
		if (view.month() != null) {
			model.addAttribute("form", NewChargeForm.of(view));
		}
		return "billing/new-charge";
	}

	@PostMapping("/app/leases/{id}/charges")
	public String create(@AuthenticationPrincipal LandlordPrincipal principal, @PathVariable long id,
			@ModelAttribute("form") NewChargeForm form, BindingResult result, Model model,
			RedirectAttributes redirect) {
		long landlordId = principal.getLandlordId();
		NewChargeForm.Amounts amounts = form.toAmounts(result);
		if (amounts == null) {
			return redisplay(landlordId, id, form, model, null);
		}
		long chargeId;
		try {
			chargeId = manualChargeService.create(landlordId, id, amounts.month(), amounts.monthlyRent(),
					amounts.maintenanceFee());
		} catch (ZeroTotalException e) {
			result.rejectValue("monthlyRent", "zero", ChargeMessages.CREATE_ZERO);
			return redisplay(landlordId, id, form, model, null);
		} catch (MonthUnavailableException e) {
			model.addAttribute("view", manualChargeService.view(landlordId, id, null));
			model.addAttribute("error", ChargeMessages.monthUnavailable(e.reason()));
			return "billing/new-charge";
		}
		return ChargeController.backToDetail(chargeId, redirect, ChargeMessages.created(amounts.month()));
	}

	/** 입력 오류: 고른 달의 화면을 다시 보여 준다 (입력한 금액은 그대로) */
	private String redisplay(long landlordId, long leaseId, NewChargeForm form, Model model, String error) {
		YearMonth month = NewChargeForm.parseMonth(form.getMonth());
		try {
			model.addAttribute("view", manualChargeService.view(landlordId, leaseId, month));
		} catch (MonthUnavailableException e) {
			model.addAttribute("view", manualChargeService.view(landlordId, leaseId, null));
			model.addAttribute("error", ChargeMessages.monthUnavailable(e.reason()));
		}
		if (error != null) {
			model.addAttribute("error", error);
		}
		return "billing/new-charge";
	}

}
