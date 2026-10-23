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
import com.rentkeeper.billing.application.ChargeEditService;
import com.rentkeeper.billing.application.ChargeEditService.ItemInput;
import com.rentkeeper.billing.application.ChargeExceptions.ChargeCanceledException;
import com.rentkeeper.billing.application.ChargeExceptions.ChargeChangedException;
import com.rentkeeper.billing.application.ChargeExceptions.HasPaymentsException;
import com.rentkeeper.billing.application.ChargeExceptions.LastItemException;
import com.rentkeeper.billing.application.ChargeExceptions.ReasonInvalidException;
import com.rentkeeper.billing.application.ChargeExceptions.ZeroTotalException;
import com.rentkeeper.billing.application.ChargeQueryService;
import com.rentkeeper.billing.application.ChargeViews.ChargeDetail;
import com.rentkeeper.billing.application.ChargeViews.ItemRow;
import com.rentkeeper.billing.domain.ChargeItemType;
import com.rentkeeper.billing.domain.ChargeStatus;
import com.rentkeeper.common.error.ResourceNotFoundException;

/**
 * 받을 월세 항목 추가·고치기·지우기와 취소 (M3 결정 7·8·12, M3-b 결정 9·10·14). 지우기·취소는 확인 화면을 거친다.
 * 화면이 보던 version과 다르면 저장하지 않고 최신 상세로 돌려보낸다. 남의 청구·항목이면 404.
 */
@Controller
public class ChargeEditController {

	private final ChargeEditService editService;

	private final ChargeQueryService queryService;

	public ChargeEditController(ChargeEditService editService, ChargeQueryService queryService) {
		this.editService = editService;
		this.queryService = queryService;
	}

	// ---------------------------------------------------------------- 항목 추가

	@GetMapping("/app/charges/{id}/items/new")
	public String addForm(@AuthenticationPrincipal LandlordPrincipal principal, @PathVariable long id, Model model,
			RedirectAttributes redirect) {
		ChargeDetail detail = queryService.detail(principal.getLandlordId(), id);
		if (detail.canceled()) {
			return ChargeController.alertDetail(id, redirect, ChargeMessages.CANCELED);
		}
		return itemForm(model, detail, null, ItemForm.adding(detail.version()));
	}

	@PostMapping("/app/charges/{id}/items")
	public String add(@AuthenticationPrincipal LandlordPrincipal principal, @PathVariable long id,
			@ModelAttribute("form") ItemForm form, BindingResult result, Model model, RedirectAttributes redirect) {
		long landlordId = principal.getLandlordId();
		ChargeDetail detail = queryService.detail(landlordId, id);
		ItemInput input = form.toInput(result);
		if (input == null) {
			return itemForm(model, detail, null, form);
		}
		try {
			ChargeStatus status = editService.addItem(landlordId, id, form.getVersion(), input);
			return ChargeController.backToDetail(id, redirect, ChargeMessages.itemsChanged("항목을 추가했어요.",
					queryService.detail(landlordId, id).totalAmount(), status));
		} catch (ZeroTotalException e) {
			result.rejectValue("amount", "zero", ChargeMessages.ZERO_TOTAL);
			return itemForm(model, detail, null, form);
		} catch (ChargeCanceledException e) {
			return ChargeController.alertDetail(id, redirect, ChargeMessages.CANCELED);
		} catch (ChargeChangedException | OptimisticLockingFailureException e) {
			return ChargeController.alertDetail(id, redirect, ChargeMessages.CHANGED);
		}
	}

	// ---------------------------------------------------------------- 항목 고치기

	@GetMapping("/app/charges/{id}/items/{itemId}/edit")
	public String editForm(@AuthenticationPrincipal LandlordPrincipal principal, @PathVariable long id,
			@PathVariable long itemId, Model model, RedirectAttributes redirect) {
		ChargeDetail detail = queryService.detail(principal.getLandlordId(), id);
		ItemRow item = item(detail, itemId);
		if (detail.canceled()) {
			return ChargeController.alertDetail(id, redirect, ChargeMessages.CANCELED);
		}
		return itemForm(model, detail, item, ItemForm.editing(detail.version(), item));
	}

	@PostMapping("/app/charges/{id}/items/{itemId}")
	public String edit(@AuthenticationPrincipal LandlordPrincipal principal, @PathVariable long id,
			@PathVariable long itemId, @ModelAttribute("form") ItemForm form, BindingResult result, Model model,
			RedirectAttributes redirect) {
		long landlordId = principal.getLandlordId();
		ChargeDetail detail = queryService.detail(landlordId, id);
		ItemRow item = item(detail, itemId);
		ItemInput input = form.toInput(result);
		if (input == null) {
			return itemForm(model, detail, item, form);
		}
		try {
			ChargeStatus status = editService.changeItem(landlordId, id, itemId, form.getVersion(), input);
			return ChargeController.backToDetail(id, redirect, ChargeMessages.itemsChanged("항목을 고쳤어요.",
					queryService.detail(landlordId, id).totalAmount(), status));
		} catch (ZeroTotalException e) {
			result.rejectValue("amount", "zero", ChargeMessages.ZERO_TOTAL);
			return itemForm(model, detail, item, form);
		} catch (ChargeCanceledException e) {
			return ChargeController.alertDetail(id, redirect, ChargeMessages.CANCELED);
		} catch (ChargeChangedException | OptimisticLockingFailureException e) {
			return ChargeController.alertDetail(id, redirect, ChargeMessages.CHANGED);
		}
	}

	// ---------------------------------------------------------------- 항목 지우기

	@GetMapping("/app/charges/{id}/items/{itemId}/delete")
	public String deleteConfirm(@AuthenticationPrincipal LandlordPrincipal principal, @PathVariable long id,
			@PathVariable long itemId, Model model, RedirectAttributes redirect) {
		ChargeDetail detail = queryService.detail(principal.getLandlordId(), id);
		ItemRow item = item(detail, itemId);
		if (detail.canceled()) {
			return ChargeController.alertDetail(id, redirect, ChargeMessages.CANCELED);
		}
		model.addAttribute("charge", detail);
		model.addAttribute("item", item);
		return "billing/item-delete";
	}

	@PostMapping("/app/charges/{id}/items/{itemId}/delete")
	public String delete(@AuthenticationPrincipal LandlordPrincipal principal, @PathVariable long id,
			@PathVariable long itemId, @RequestParam(name = "version", defaultValue = "-1") long version,
			RedirectAttributes redirect) {
		long landlordId = principal.getLandlordId();
		item(queryService.detail(landlordId, id), itemId);
		try {
			ChargeStatus status = editService.removeItem(landlordId, id, itemId, version);
			return ChargeController.backToDetail(id, redirect, ChargeMessages.itemsChanged("항목을 지웠어요.",
					queryService.detail(landlordId, id).totalAmount(), status));
		} catch (LastItemException e) {
			return ChargeController.alertDetail(id, redirect, ChargeMessages.LAST_ITEM);
		} catch (ZeroTotalException e) {
			return ChargeController.alertDetail(id, redirect, ChargeMessages.ZERO_TOTAL);
		} catch (ChargeCanceledException e) {
			return ChargeController.alertDetail(id, redirect, ChargeMessages.CANCELED);
		} catch (ChargeChangedException | OptimisticLockingFailureException e) {
			return ChargeController.alertDetail(id, redirect, ChargeMessages.CHANGED);
		}
	}

	// ---------------------------------------------------------------- 취소 (M3 결정
	// 7·8)

	@GetMapping("/app/charges/{id}/cancel")
	public String cancelConfirm(@AuthenticationPrincipal LandlordPrincipal principal, @PathVariable long id,
			Model model, RedirectAttributes redirect) {
		ChargeDetail detail = queryService.detail(principal.getLandlordId(), id);
		if (detail.canceled()) {
			return ChargeController.alertDetail(id, redirect, ChargeMessages.CANCELED);
		}
		model.addAttribute("charge", detail);
		model.addAttribute("reason", "");
		return "billing/cancel";
	}

	@PostMapping("/app/charges/{id}/cancel")
	public String cancel(@AuthenticationPrincipal LandlordPrincipal principal, @PathVariable long id,
			@RequestParam(name = "version", defaultValue = "-1") long version,
			@RequestParam(name = "reason", defaultValue = "") String reason, Model model, RedirectAttributes redirect) {
		long landlordId = principal.getLandlordId();
		ChargeDetail detail = queryService.detail(landlordId, id);
		try {
			editService.cancel(landlordId, id, version, reason);
		} catch (ReasonInvalidException e) {
			model.addAttribute("charge", detail);
			model.addAttribute("reason", reason);
			model.addAttribute("error", ChargeMessages.CANCEL_REASON_REQUIRED);
			return "billing/cancel";
		} catch (HasPaymentsException e) {
			return ChargeController.alertDetail(id, redirect, ChargeMessages.HAS_PAYMENTS);
		} catch (ChargeCanceledException e) {
			return ChargeController.alertDetail(id, redirect, ChargeMessages.CANCELED);
		} catch (ChargeChangedException | OptimisticLockingFailureException e) {
			return ChargeController.alertDetail(id, redirect, ChargeMessages.CHANGED);
		}
		return ChargeController.backToDetail(id, redirect, ChargeMessages.canceledDone());
	}

	private String itemForm(Model model, ChargeDetail detail, ItemRow item, ItemForm form) {
		model.addAttribute("charge", detail);
		model.addAttribute("item", item);
		model.addAttribute("form", form);
		model.addAttribute("types", ChargeItemType.values());
		return "billing/item-form";
	}

	/** 이 청구의 항목 (다른 청구·다른 임대인의 항목이면 404) */
	private static ItemRow item(ChargeDetail detail, long itemId) {
		return detail.items().stream().filter(i -> i.id() == itemId).findFirst()
				.orElseThrow(() -> new ResourceNotFoundException("charge item"));
	}

}
