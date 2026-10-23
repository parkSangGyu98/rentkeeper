package com.rentkeeper.dashboard.web;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.rentkeeper.auth.security.LandlordPrincipal;
import com.rentkeeper.common.error.ResourceNotFoundException;
import com.rentkeeper.dashboard.application.DashboardService;

/**
 * 임대인 대시보드 (/app/dashboard, M4 결정 14). 로그인 후 첫 화면(/app)도 이 화면이다. 건물은
 * 주소(?propertyId=)로 고른다(GET, 뒤로 가기·저장 가능). 남의 건물이면 404.
 */
@Controller
public class DashboardController {

	private final DashboardService dashboardService;

	public DashboardController(DashboardService dashboardService) {
		this.dashboardService = dashboardService;
	}

	/**
	 * @param propertyId
	 *            고를 건물 (없거나 빈 값이면 모든 건물, 숫자가 아니면 404)
	 */
	@GetMapping({"/app", "/app/dashboard"})
	public String dashboard(@AuthenticationPrincipal LandlordPrincipal principal,
			@RequestParam(name = "propertyId", defaultValue = "") String propertyId, Model model) {
		model.addAttribute("view", dashboardService.view(principal.getLandlordId(), parse(propertyId)));
		return "dashboard/home";
	}

	private static Long parse(String raw) {
		if (raw.isBlank()) {
			return null;
		}
		try {
			return Long.valueOf(raw.strip());
		} catch (NumberFormatException e) {
			throw new ResourceNotFoundException("property");
		}
	}

}
