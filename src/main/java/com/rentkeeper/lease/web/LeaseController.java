package com.rentkeeper.lease.web;

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
import com.rentkeeper.common.error.ResourceNotFoundException;
import com.rentkeeper.lease.application.LeaseExceptions.ExistingTenantException;
import com.rentkeeper.lease.application.LeaseExceptions.LeaseOverlapException;
import com.rentkeeper.lease.application.LeaseService;
import com.rentkeeper.lease.application.LeaseViews.AccountPrefill;
import com.rentkeeper.lease.application.LeaseViews.LeaseRegistration;
import com.rentkeeper.lease.application.TenantLinkService;
import com.rentkeeper.property.application.PropertyViews.UnitView;

/**
 * 계약 등록(입력 → 확인 → 저장)과 계약 상세 (LEASE-1, /app/leases). landlordId는 로그인 정보에서만 얻는다.
 * 호실·계약이 남의 것이면 404 (ADR-018).
 */
@Controller
public class LeaseController {

	static final String OVERLAP_MESSAGE = "이 호실에 기간이 겹치는 계약이 있어요. 앞 계약의 나가는 날부터 새 계약을 시작할 수 있어요.";

	private final LeaseService leaseService;

	private final TenantLinkService tenantLinkService;

	public LeaseController(LeaseService leaseService, TenantLinkService tenantLinkService) {
		this.leaseService = leaseService;
		this.tenantLinkService = tenantLinkService;
	}

	@GetMapping("/app/leases/new")
	public String newForm(@AuthenticationPrincipal LandlordPrincipal principal, @RequestParam long unitId,
			Model model) {
		UnitView unit = leaseService.unitForNewLease(principal.getLandlordId(), unitId);
		AccountPrefill prefill = leaseService.accountPrefill(principal.getLandlordId()).orElse(null);
		model.addAttribute("form", LeaseForm.empty(unitId, prefill));
		model.addAttribute("unit", unit);
		model.addAttribute("accountPrefilled", prefill != null);
		return "lease/form";
	}

	/** 1단계: 입력 검증 → 확인 화면 (아직 저장하지 않는다) */
	@PostMapping("/app/leases/confirm")
	public String confirm(@AuthenticationPrincipal LandlordPrincipal principal, @ModelAttribute("form") LeaseForm form,
			BindingResult result, Model model) {
		long landlordId = principal.getLandlordId();
		UnitView unit = leaseService.unitForNewLease(landlordId, requireUnitId(form));
		model.addAttribute("unit", unit);
		LeaseRegistration registration = form.toRegistration(result);
		if (registration == null) {
			return "lease/form";
		}
		try {
			leaseService.checkOverlap(landlordId, unit.id(), registration.terms().startDate(),
					registration.terms().endDate());
		} catch (LeaseOverlapException e) {
			result.reject("overlap", OVERLAP_MESSAGE);
			return "lease/form";
		}
		model.addAttribute("existingTenant",
				leaseService.existingTenant(landlordId, registration.tenantPhone()).orElse(null));
		model.addAttribute("registration", registration);
		model.addAttribute("thisMonthNotAutomatic", leaseService.thisMonthNotAutomatic(registration.terms()));
		return "lease/confirm";
	}

	/** 확인 화면에서 [고치기] → 입력한 값 그대로 입력 화면으로 */
	@PostMapping("/app/leases/edit-draft")
	public String backToForm(@AuthenticationPrincipal LandlordPrincipal principal,
			@ModelAttribute("form") LeaseForm form, Model model) {
		model.addAttribute("unit", leaseService.unitForNewLease(principal.getLandlordId(), requireUnitId(form)));
		form.setUseExistingTenant(false);
		return "lease/form";
	}

	/** 2단계: 확인 화면에서 [등록하기] → 다시 검증하고 저장 */
	@PostMapping("/app/leases")
	public String register(@AuthenticationPrincipal LandlordPrincipal principal, @ModelAttribute("form") LeaseForm form,
			BindingResult result, Model model, RedirectAttributes redirect) {
		long landlordId = principal.getLandlordId();
		UnitView unit = leaseService.unitForNewLease(landlordId, requireUnitId(form));
		model.addAttribute("unit", unit);
		LeaseRegistration registration = form.toRegistration(result);
		if (registration == null) {
			return "lease/form";
		}
		long leaseId;
		try {
			leaseId = leaseService.register(landlordId, registration);
		} catch (LeaseOverlapException e) {
			result.reject("overlap", OVERLAP_MESSAGE);
			return "lease/form";
		} catch (ExistingTenantException e) {
			// 같은 번호의 세입자가 있다 → 확인 화면에서 "이 세입자로 계약할까요?"를 보여 준다
			model.addAttribute("existingTenant",
					leaseService.existingTenant(landlordId, registration.tenantPhone()).orElse(e.existing()));
			model.addAttribute("registration", registration);
			model.addAttribute("thisMonthNotAutomatic", leaseService.thisMonthNotAutomatic(registration.terms()));
			return "lease/confirm";
		}
		redirect.addFlashAttribute("notice", "계약을 등록했어요.");
		return "redirect:/app/leases/" + leaseId;
	}

	@GetMapping("/app/leases/{id}")
	public String detail(@AuthenticationPrincipal LandlordPrincipal principal, @PathVariable long id, Model model) {
		model.addAttribute("lease", leaseService.detail(principal.getLandlordId(), id));
		model.addAttribute("tenantLink", tenantLinkService.status(principal.getLandlordId(), id));
		return "lease/detail";
	}

	private static long requireUnitId(LeaseForm form) {
		if (form.getUnitId() == null) {
			throw new ResourceNotFoundException("unit");
		}
		return form.getUnitId();
	}

}
