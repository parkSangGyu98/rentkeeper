package com.rentkeeper.billing.web;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.rentkeeper.auth.security.LandlordPrincipal;
import com.rentkeeper.billing.application.ChargeQueryService;
import com.rentkeeper.billing.application.ChargeViews.StatusFilter;

/**
 * 받을 월세 목록과 상세. 달과 상태는 주소로 고른다(GET 링크, 뒤로 가기·저장 가능, M3 결정 16). 남의 청구면 404.
 */
@Controller
public class ChargeController {

	/** 상세 위쪽에 빨간 상자로 보일 안내 (충돌·취소 등). 일반 안내는 레이아웃의 notice */
	static final String ALERT = "chargeAlert";

	private final ChargeQueryService queryService;

	public ChargeController(ChargeQueryService queryService) {
		this.queryService = queryService;
	}

	/**
	 * @param month
	 *            "2026-10" 형식. 없거나 잘못되면 이번 달
	 * @param status
	 *            고를 상태 (예: "paid", M3-b 결정 12). 없거나 모르는 값이면 전체
	 */
	@GetMapping("/app/charges")
	public String list(@AuthenticationPrincipal LandlordPrincipal principal,
			@RequestParam(name = "month", defaultValue = "") String month,
			@RequestParam(name = "status", defaultValue = "") String status, Model model) {
		model.addAttribute("view",
				queryService.month(principal.getLandlordId(), parseMonth(month), StatusFilter.parse(status)));
		return "billing/list";
	}

	@GetMapping("/app/charges/{id}")
	public String detail(@AuthenticationPrincipal LandlordPrincipal principal, @PathVariable long id, Model model) {
		model.addAttribute("charge", queryService.detail(principal.getLandlordId(), id));
		return "billing/detail";
	}

	static String backToDetail(long id, RedirectAttributes redirect, String notice) {
		redirect.addFlashAttribute("notice", notice);
		return "redirect:/app/charges/" + id;
	}

	static String alertDetail(long id, RedirectAttributes redirect, String alert) {
		redirect.addFlashAttribute(ALERT, alert);
		return "redirect:/app/charges/" + id;
	}

	private YearMonth parseMonth(String raw) {
		try {
			return raw.isBlank() ? queryService.thisMonth() : YearMonth.parse(raw.strip());
		} catch (DateTimeParseException e) {
			return queryService.thisMonth();
		}
	}

}
