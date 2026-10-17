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
import com.rentkeeper.property.application.PropertyExceptions.DuplicatePropertyNameException;
import com.rentkeeper.property.application.PropertyExceptions.PropertyHasUnitsException;
import com.rentkeeper.property.application.PropertyService;
import com.rentkeeper.property.domain.UnitType;
import com.rentkeeper.property.web.PropertyForms.PropertyForm;
import com.rentkeeper.property.web.PropertyForms.UnitForm;

/**
 * 건물 목록·등록·상세·수정·삭제 (/app/properties, PROP-1). landlordId는 로그인 정보에서만 얻는다 (요청
 * 값으로 받지 않는다, ADR-018). 삭제는 확인 화면(GET)을 거쳐 POST로 한다 (M2 결정 13).
 */
@Controller
public class PropertyController {

	static final String DUPLICATE_NAME = "이미 같은 이름의 건물이 있어요. 다른 이름으로 지어 주세요 (예: 행복빌라 2동)";

	private final PropertyService propertyService;

	public PropertyController(PropertyService propertyService) {
		this.propertyService = propertyService;
	}

	@GetMapping("/app/properties")
	public String list(@AuthenticationPrincipal LandlordPrincipal principal, Model model) {
		model.addAttribute("properties", propertyService.list(principal.getLandlordId()));
		return "property/list";
	}

	@GetMapping("/app/properties/new")
	public String newForm(Model model) {
		model.addAttribute("form", PropertyForm.empty());
		model.addAttribute("editing", false);
		return "property/form";
	}

	@PostMapping("/app/properties")
	public String create(@AuthenticationPrincipal LandlordPrincipal principal,
			@Validated @ModelAttribute("form") PropertyForm form, BindingResult result, Model model,
			RedirectAttributes redirect) {
		model.addAttribute("editing", false);
		if (result.hasErrors()) {
			return "property/form";
		}
		long id;
		try {
			id = propertyService.create(principal.getLandlordId(), form.toInput());
		} catch (DuplicatePropertyNameException e) {
			result.rejectValue("name", "duplicate", DUPLICATE_NAME);
			return "property/form";
		}
		redirect.addFlashAttribute("notice", "건물을 등록했어요. 이제 호실을 추가해 보세요.");
		return "redirect:/app/properties/" + id;
	}

	@GetMapping("/app/properties/{id}")
	public String detail(@AuthenticationPrincipal LandlordPrincipal principal, @PathVariable long id, Model model) {
		model.addAttribute("property", propertyService.detail(principal.getLandlordId(), id));
		if (!model.containsAttribute("unitForm")) {
			model.addAttribute("unitForm", UnitForm.empty());
		}
		model.addAttribute("unitTypes", UnitType.values());
		return "property/detail";
	}

	@GetMapping("/app/properties/{id}/edit")
	public String editForm(@AuthenticationPrincipal LandlordPrincipal principal, @PathVariable long id, Model model) {
		model.addAttribute("form", PropertyForm.of(propertyService.currentInput(principal.getLandlordId(), id)));
		model.addAttribute("editing", true);
		model.addAttribute("propertyId", id);
		return "property/form";
	}

	@PostMapping("/app/properties/{id}")
	public String update(@AuthenticationPrincipal LandlordPrincipal principal, @PathVariable long id,
			@Validated @ModelAttribute("form") PropertyForm form, BindingResult result, Model model,
			RedirectAttributes redirect) {
		model.addAttribute("editing", true);
		model.addAttribute("propertyId", id);
		if (result.hasErrors()) {
			// 남의 건물이면 오류 화면 대신 404 (존재 여부를 알려 주지 않는다)
			propertyService.currentInput(principal.getLandlordId(), id);
			return "property/form";
		}
		try {
			propertyService.update(principal.getLandlordId(), id, form.toInput());
		} catch (DuplicatePropertyNameException e) {
			result.rejectValue("name", "duplicate", DUPLICATE_NAME);
			return "property/form";
		}
		redirect.addFlashAttribute("notice", "건물 정보를 바꿨어요.");
		return "redirect:/app/properties/" + id;
	}

	@GetMapping("/app/properties/{id}/delete")
	public String deleteConfirm(@AuthenticationPrincipal LandlordPrincipal principal, @PathVariable long id,
			Model model) {
		model.addAttribute("property", propertyService.detail(principal.getLandlordId(), id));
		return "property/delete";
	}

	@PostMapping("/app/properties/{id}/delete")
	public String delete(@AuthenticationPrincipal LandlordPrincipal principal, @PathVariable long id,
			RedirectAttributes redirect) {
		try {
			propertyService.delete(principal.getLandlordId(), id);
		} catch (PropertyHasUnitsException e) {
			redirect.addFlashAttribute("notice", "호실이 있는 건물은 지울 수 없어요. 호실을 먼저 지워 주세요.");
			return "redirect:/app/properties/" + id;
		}
		redirect.addFlashAttribute("notice", "건물을 지웠어요.");
		return "redirect:/app/properties";
	}

}
