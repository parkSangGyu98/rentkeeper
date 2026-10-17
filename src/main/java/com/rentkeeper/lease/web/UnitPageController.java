package com.rentkeeper.lease.web;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import com.rentkeeper.auth.security.LandlordPrincipal;
import com.rentkeeper.lease.application.LeaseService;

/**
 * 호실 화면 (/app/units/{id}): 상태, 현재 계약, 지난 계약. 계약 정보가 필요해 lease 모듈이 맡는다(property는
 * lease에 기댈 수 없다, ADR-020).
 */
@Controller
public class UnitPageController {

	private final LeaseService leaseService;

	public UnitPageController(LeaseService leaseService) {
		this.leaseService = leaseService;
	}

	@GetMapping("/app/units/{id}")
	public String unit(@AuthenticationPrincipal LandlordPrincipal principal, @PathVariable long id, Model model) {
		model.addAttribute("page", leaseService.unitPage(principal.getLandlordId(), id));
		return "lease/unit";
	}

}
