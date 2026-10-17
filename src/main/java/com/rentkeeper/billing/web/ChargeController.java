package com.rentkeeper.billing.web;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.rentkeeper.auth.security.LandlordPrincipal;
import com.rentkeeper.billing.application.ChargeQueryService;

/**
 * 받을 월세 목록 (M3-a 읽기 전용). 달은 주소로 고른다(GET 링크, 뒤로 가기·저장 가능, M3 결정 16). 입금·수정은
 * M3-b에서 붙인다.
 */
@Controller
public class ChargeController {

	private final ChargeQueryService queryService;

	public ChargeController(ChargeQueryService queryService) {
		this.queryService = queryService;
	}

	/**
	 * @param month
	 *            "2026-10" 형식. 없거나 잘못되면 이번 달
	 */
	@GetMapping("/app/charges")
	public String list(@AuthenticationPrincipal LandlordPrincipal principal,
			@RequestParam(name = "month", defaultValue = "") String month, Model model) {
		model.addAttribute("view", queryService.month(principal.getLandlordId(), parseMonth(month)));
		return "billing/list";
	}

	private YearMonth parseMonth(String raw) {
		try {
			return raw.isBlank() ? queryService.thisMonth() : YearMonth.parse(raw.strip());
		} catch (DateTimeParseException e) {
			return queryService.thisMonth();
		}
	}

}
