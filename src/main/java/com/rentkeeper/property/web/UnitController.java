package com.rentkeeper.property.web;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.rentkeeper.auth.security.LandlordPrincipal;
import com.rentkeeper.property.application.PropertyExceptions.DuplicateUnitNameException;
import com.rentkeeper.property.application.PropertyExceptions.UnitHasLeasesException;
import com.rentkeeper.property.application.PropertyService;
import com.rentkeeper.property.application.PropertyViews.UnitView;
import com.rentkeeper.property.domain.UnitType;
import com.rentkeeper.property.web.PropertyForms.UnitForm;

/**
 * 호실 추가·수정·삭제 (PROP-2). 호실 상세(현재 계약 등)는 계약 모듈이 맡는다(/app/units/{id}, M2 커밋 4).
 */
@Controller
public class UnitController {

	static final String DUPLICATE_NAME = "이 건물에 같은 이름의 호실이 이미 있어요. 다른 이름으로 입력해 주세요";

	private final PropertyService propertyService;

	public UnitController(PropertyService propertyService) {
		this.propertyService = propertyService;
	}

	/** 건물 상세 화면의 호실 추가 폼 */
	@PostMapping("/app/properties/{propertyId}/units")
	public String add(@AuthenticationPrincipal LandlordPrincipal principal, @PathVariable long propertyId,
			@Validated @ModelAttribute("unitForm") UnitForm form, BindingResult result, Model model,
			RedirectAttributes redirect) {
		if (!result.hasErrors()) {
			try {
				propertyService.addUnit(principal.getLandlordId(), propertyId, form.toInput());
				redirect.addFlashAttribute("notice", "호실을 추가했어요.");
				return "redirect:/app/properties/" + propertyId;
			} catch (DuplicateUnitNameException e) {
				result.rejectValue("name", "duplicate", DUPLICATE_NAME);
			}
		}
		// 오류가 있으면 입력값을 살려 건물 상세를 다시 보여 준다 (남의 건물이면 여기서 404)
		model.addAttribute("property", propertyService.detail(principal.getLandlordId(), propertyId));
		model.addAttribute("unitTypes", UnitType.values());
		return "property/detail";
	}

	@GetMapping("/app/units/{id}/edit")
	public String editForm(@AuthenticationPrincipal LandlordPrincipal principal, @PathVariable long id, Model model) {
		UnitView unit = propertyService.unit(principal.getLandlordId(), id);
		model.addAttribute("unit", unit);
		model.addAttribute("form", new UnitForm(unit.name(), unit.type(), PropertyForms.nullToEmpty(unit.memo())));
		model.addAttribute("unitTypes", UnitType.values());
		return "property/unit-form";
	}

	@PostMapping("/app/units/{id}")
	public String update(@AuthenticationPrincipal LandlordPrincipal principal, @PathVariable long id,
			@Validated @ModelAttribute("form") UnitForm form, BindingResult result, Model model,
			RedirectAttributes redirect) {
		UnitView unit = propertyService.unit(principal.getLandlordId(), id);
		model.addAttribute("unit", unit);
		model.addAttribute("unitTypes", UnitType.values());
		if (result.hasErrors()) {
			return "property/unit-form";
		}
		try {
			propertyService.updateUnit(principal.getLandlordId(), id, form.toInput());
		} catch (DuplicateUnitNameException e) {
			result.rejectValue("name", "duplicate", DUPLICATE_NAME);
			return "property/unit-form";
		}
		redirect.addFlashAttribute("notice", "호실 정보를 바꿨어요.");
		return "redirect:/app/properties/" + unit.propertyId();
	}

	@GetMapping("/app/units/{id}/delete")
	public String deleteConfirm(@AuthenticationPrincipal LandlordPrincipal principal, @PathVariable long id,
			Model model) {
		model.addAttribute("unit", propertyService.unit(principal.getLandlordId(), id));
		return "property/unit-delete";
	}

	@PostMapping("/app/units/{id}/delete")
	public String delete(@AuthenticationPrincipal LandlordPrincipal principal, @PathVariable long id,
			RedirectAttributes redirect) {
		UnitView unit = propertyService.unit(principal.getLandlordId(), id);
		try {
			propertyService.deleteUnit(principal.getLandlordId(), id);
		} catch (UnitHasLeasesException e) {
			redirect.addFlashAttribute("notice", "계약 기록이 있는 호실은 지울 수 없어요.");
			return "redirect:/app/properties/" + unit.propertyId();
		}
		redirect.addFlashAttribute("notice", unit.name() + "을(를) 지웠어요.");
		return "redirect:/app/properties/" + unit.propertyId();
	}

}
