package com.rentkeeper.billing.web;

import org.springframework.dao.OptimisticLockingFailureException;
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
import com.rentkeeper.billing.application.ChargeExceptions.ChargeCanceledException;
import com.rentkeeper.billing.application.ChargeExceptions.ChargeChangedException;
import com.rentkeeper.billing.application.ChargeExceptions.OverpaymentException;
import com.rentkeeper.billing.application.ChargeExceptions.PaymentInputException;
import com.rentkeeper.billing.application.ChargeQueryService;
import com.rentkeeper.billing.application.ChargeViews.ChargeDetail;
import com.rentkeeper.billing.application.PaymentService;
import com.rentkeeper.billing.application.PaymentService.PaymentInput;
import com.rentkeeper.billing.domain.ChargeStatus;

/**
 * 입금 기록 ([다 받았어요]·[일부 받았어요], PAY-1·2). 둘 다 확인 화면을 거쳐 같은 POST로 기록한다(M3-b 결정 2).
 * 화면이 보던 version과 다르면 저장하지 않고 최신 상세로 돌려보내며 안내한다(결정 4). 남의 청구면 404.
 */
@Controller
public class PaymentController {

	private final PaymentService paymentService;

	private final ChargeQueryService queryService;

	public PaymentController(PaymentService paymentService, ChargeQueryService queryService) {
		this.paymentService = paymentService;
		this.queryService = queryService;
	}

	@GetMapping("/app/charges/{id}/payments/new")
	public String form(@AuthenticationPrincipal LandlordPrincipal principal, @PathVariable long id,
			@RequestParam(name = "full", defaultValue = "false") boolean full, Model model,
			RedirectAttributes redirect) {
		ChargeDetail detail = queryService.detail(principal.getLandlordId(), id);
		if (!detail.canPay()) {
			return ChargeController.backToDetail(id, redirect,
					detail.canceled() ? ChargeMessages.CANCELED : ChargeMessages.ALREADY_PAID);
		}
		model.addAttribute("charge", detail);
		model.addAttribute("form", PaymentForm.of(detail.version(), full, detail.balance(), queryService.today()));
		return "billing/payment-form";
	}

	@PostMapping("/app/charges/{id}/payments")
	public String record(@AuthenticationPrincipal LandlordPrincipal principal, @PathVariable long id,
			@ModelAttribute("form") PaymentForm form, BindingResult result, Model model, RedirectAttributes redirect) {
		long landlordId = principal.getLandlordId();
		ChargeDetail detail = queryService.detail(landlordId, id);
		model.addAttribute("charge", detail);
		PaymentInput input = form.toInput(result);
		if (input == null) {
			return "billing/payment-form";
		}
		ChargeStatus status;
		try {
			status = paymentService.record(landlordId, id, form.getVersion(), input);
		} catch (PaymentInputException e) {
			result.rejectValue(ChargeMessages.isAmount(e) ? "amount" : "paidOn", e.problem().name(),
					ChargeMessages.paymentInput(e, detail));
			return "billing/payment-form";
		} catch (OverpaymentException e) {
			model.addAttribute("overpay", ChargeMessages.overpay(e.over()));
			form.setOverpayConfirmed(true);
			return "billing/payment-form";
		} catch (ChargeCanceledException e) {
			return ChargeController.alertDetail(id, redirect, ChargeMessages.CANCELED);
		} catch (ChargeChangedException | OptimisticLockingFailureException e) {
			return ChargeController.alertDetail(id, redirect,
					ChargeMessages.changed(queryService.status(landlordId, id)));
		}
		long balance = queryService.detail(landlordId, id).balance();
		return ChargeController.backToDetail(id, redirect, ChargeMessages.recorded(status, balance));
	}

}
