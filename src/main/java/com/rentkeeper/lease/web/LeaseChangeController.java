package com.rentkeeper.lease.web;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.rentkeeper.auth.security.LandlordPrincipal;
import com.rentkeeper.common.error.ResourceNotFoundException;
import com.rentkeeper.lease.application.LeaseChangeService;
import com.rentkeeper.lease.application.LeaseChangeService.EditView;
import com.rentkeeper.lease.application.LeaseChangeService.LeaseEdit;
import com.rentkeeper.lease.application.LeaseExceptions.ChangeAfterEndException;
import com.rentkeeper.lease.application.LeaseExceptions.DuplicateTenantPhoneException;
import com.rentkeeper.lease.application.LeaseExceptions.LeaseEndedException;
import com.rentkeeper.lease.application.LeaseExceptions.LeaseOverlapException;
import com.rentkeeper.lease.application.LeaseService;
import com.rentkeeper.lease.application.LeaseViews.LeaseDetail;
import com.rentkeeper.lease.domain.InvalidLeaseValueException;
import com.rentkeeper.lease.domain.RentSchedule.RentChangeException;
import com.rentkeeper.lease.web.RentChangeForm.RentChange;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 계약 수정·월세 변경·미래 변경 취소·계약 종료·삭제 (LEASE-2·3). 되돌리기 어려운 작업(취소·종료·삭제)은 확인 화면(GET)을
 * 거쳐 POST로 한다(M2 결정 13). 남의 계약이면 404, 종료한 계약은 고칠 수 없다는 안내와 함께 상세로 돌려보낸다.
 */
@Controller
public class LeaseChangeController {

	private final LeaseChangeService changeService;

	private final LeaseService leaseService;

	public LeaseChangeController(LeaseChangeService changeService, LeaseService leaseService) {
		this.changeService = changeService;
		this.leaseService = leaseService;
	}

	// ---------------------------------------------------------------- 수정

	@GetMapping("/app/leases/{id}/edit")
	public String editForm(@AuthenticationPrincipal LandlordPrincipal principal, @PathVariable long id, Model model) {
		EditView view = changeService.editView(principal.getLandlordId(), id);
		model.addAttribute("view", view);
		model.addAttribute("form", LeaseEditForm.of(view.current()));
		return "lease/edit";
	}

	@PostMapping("/app/leases/{id}")
	public String edit(@AuthenticationPrincipal LandlordPrincipal principal, @PathVariable long id,
			@ModelAttribute("form") LeaseEditForm form, BindingResult result, Model model,
			RedirectAttributes redirect) {
		EditView view = changeService.editView(principal.getLandlordId(), id);
		model.addAttribute("view", view);
		LeaseEdit edit = form.toEdit(result, view.startDate());
		if (edit == null) {
			return "lease/edit";
		}
		try {
			changeService.edit(principal.getLandlordId(), id, edit);
		} catch (LeaseOverlapException e) {
			result.rejectValue("endDate", "overlap", "이 나가는 날이면 같은 호실의 다른 계약과 기간이 겹쳐요");
			return "lease/edit";
		} catch (ChangeAfterEndException e) {
			result.rejectValue("endDate", "changeAfterEnd", "새 나가는 날 뒤로 잡힌 월세 변경이 있어요. 그 변경을 먼저 취소해 주세요");
			return "lease/edit";
		} catch (DuplicateTenantPhoneException e) {
			result.rejectValue("tenantPhone", "duplicate", "이 휴대폰 번호는 다른 세입자가 이미 쓰고 있어요");
			return "lease/edit";
		}
		redirect.addFlashAttribute("notice", "계약을 고쳤어요.");
		return "redirect:/app/leases/" + id;
	}

	// ---------------------------------------------------------------- 월세 변경

	@GetMapping("/app/leases/{id}/rent-change")
	public String rentChangeForm(@AuthenticationPrincipal LandlordPrincipal principal, @PathVariable long id,
			Model model) {
		model.addAttribute("view", changeService.rentChangeView(principal.getLandlordId(), id));
		model.addAttribute("form", new RentChangeForm());
		return "lease/rent-change";
	}

	@PostMapping("/app/leases/{id}/rent-change")
	public String changeRent(@AuthenticationPrincipal LandlordPrincipal principal, @PathVariable long id,
			@ModelAttribute("form") RentChangeForm form, BindingResult result, Model model,
			RedirectAttributes redirect) {
		model.addAttribute("view", changeService.rentChangeView(principal.getLandlordId(), id));
		RentChange change = form.toChange(result);
		if (change == null) {
			return "lease/rent-change";
		}
		try {
			changeService.changeRent(principal.getLandlordId(), id, change.month(), change.monthlyRent(),
					change.maintenanceFee(), change.reason());
		} catch (RentChangeException e) {
			result.rejectValue("effectiveMonth", e.violation().name(), rentChangeMessage(e));
			return "lease/rent-change";
		}
		redirect.addFlashAttribute("notice",
				change.month().getYear() + "년 " + change.month().getMonthValue() + "월부터 월세를 바꿨어요.");
		return "redirect:/app/leases/" + id;
	}

	@GetMapping("/app/leases/{id}/rent-history/{historyId}/cancel")
	public String cancelConfirm(@AuthenticationPrincipal LandlordPrincipal principal, @PathVariable long id,
			@PathVariable long historyId, Model model, RedirectAttributes redirect) {
		try {
			model.addAttribute("change", changeService.cancellableChange(principal.getLandlordId(), id, historyId));
		} catch (RentChangeException e) {
			return backToDetail(id, redirect, rentChangeMessage(e));
		}
		model.addAttribute("leaseId", id);
		return "lease/rent-cancel";
	}

	@PostMapping("/app/leases/{id}/rent-history/{historyId}/cancel")
	public String cancel(@AuthenticationPrincipal LandlordPrincipal principal, @PathVariable long id,
			@PathVariable long historyId, RedirectAttributes redirect) {
		try {
			changeService.cancelChange(principal.getLandlordId(), id, historyId);
		} catch (RentChangeException e) {
			return backToDetail(id, redirect, rentChangeMessage(e));
		}
		return backToDetail(id, redirect, "예정된 월세 변경을 취소했어요.");
	}

	// ---------------------------------------------------------------- 종료

	@GetMapping("/app/leases/{id}/end")
	public String endConfirm(@AuthenticationPrincipal LandlordPrincipal principal, @PathVariable long id, Model model) {
		model.addAttribute("lease", activeDetail(principal, id));
		model.addAttribute("endedOn", changeService.today().toString());
		model.addAttribute("today", changeService.today().toString());
		return "lease/end";
	}

	@PostMapping("/app/leases/{id}/end")
	public String end(@AuthenticationPrincipal LandlordPrincipal principal, @PathVariable long id,
			@RequestParam(name = "endedOn", defaultValue = "") String endedOn, Model model,
			RedirectAttributes redirect) {
		LocalDate day;
		try {
			day = LocalDate.parse(endedOn.strip());
			changeService.end(principal.getLandlordId(), id, day);
		} catch (DateTimeParseException | InvalidLeaseValueException e) {
			model.addAttribute("lease", activeDetail(principal, id));
			model.addAttribute("endedOn", endedOn);
			model.addAttribute("today", changeService.today().toString());
			model.addAttribute("error", "실제로 끝난 날은 들어오는 날부터 오늘까지 중에서 골라 주세요. 앞으로 나갈 예정이면 계약 수정에서 나가는 날을 고쳐 주세요");
			return "lease/end";
		}
		return backToDetail(id, redirect, "계약을 종료했어요. 호실 상태에서 빠지고, 이 계약은 더 이상 고칠 수 없어요.");
	}

	// ---------------------------------------------------------------- 삭제

	@GetMapping("/app/leases/{id}/delete")
	public String deleteConfirm(@AuthenticationPrincipal LandlordPrincipal principal, @PathVariable long id,
			Model model) {
		model.addAttribute("lease", leaseService.detail(principal.getLandlordId(), id));
		return "lease/delete";
	}

	@PostMapping("/app/leases/{id}/delete")
	public String delete(@AuthenticationPrincipal LandlordPrincipal principal, @PathVariable long id,
			RedirectAttributes redirect) {
		long unitId = changeService.delete(principal.getLandlordId(), id);
		redirect.addFlashAttribute("notice", "계약을 지웠어요.");
		return "redirect:/app/units/" + unitId;
	}

	// ---------------------------------------------------------------- 공통

	/** 종료한 계약을 고치려 하면 상세로 돌려보내며 안내한다 */
	@ExceptionHandler(LeaseEndedException.class)
	public String ended(HttpServletRequest request, RedirectAttributes redirect) {
		String path = request.getRequestURI();
		String id = path.replaceAll("^/app/leases/([0-9]+).*$", "$1");
		return backToDetail(Long.parseLong(id), redirect, "종료한 계약은 고칠 수 없어요.");
	}

	private LeaseDetail activeDetail(LandlordPrincipal principal, long id) {
		return leaseService.activeDetail(principal.getLandlordId(), id);
	}

	private static String backToDetail(long id, RedirectAttributes redirect, String notice) {
		redirect.addFlashAttribute("notice", notice);
		return "redirect:/app/leases/" + id;
	}

	private static String rentChangeMessage(RentChangeException e) {
		return switch (e.violation()) {
			case LEASE_ENDED -> "종료한 계약은 고칠 수 없어요";
			case MONTH_IN_PAST -> "지난 달부터는 바꿀 수 없어요. 이번 달 이후로 골라 주세요";
			case FIRST_MONTH -> "첫 달 금액은 [계약 고치기]에서 고쳐 주세요";
			case AFTER_LEASE -> "계약 기간 안의 달을 골라 주세요";
			case ALREADY_STARTED -> "이미 적용 중인 달의 금액은 바꿀 수 없어요. 다음 달 이후로 골라 주세요";
			case NOT_FOUND -> throw new ResourceNotFoundException("rent history");
		};
	}

}
