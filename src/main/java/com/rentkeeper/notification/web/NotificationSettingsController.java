package com.rentkeeper.notification.web;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.rentkeeper.auth.security.LandlordPrincipal;
import com.rentkeeper.landlord.application.LandlordService;
import com.rentkeeper.notification.application.NotificationPreferences;
import com.rentkeeper.notification.application.NotificationPreferences.Settings;

/**
 * 알림 설정 (M5 결정 13). 로그인한 임대인의 설정만 읽고 쓴다(남의 임대인 ID를 받지 않는다). 체크하지 않은 칸은 폼에 오지
 * 않으므로 "꺼짐"이다.
 */
@Controller
public class NotificationSettingsController {

	static final String SAVED = "알림 설정을 저장했어요.";

	private final NotificationPreferences preferences;

	private final LandlordService landlordService;

	public NotificationSettingsController(NotificationPreferences preferences, LandlordService landlordService) {
		this.preferences = preferences;
		this.landlordService = landlordService;
	}

	@GetMapping("/app/settings/notifications")
	public String form(@AuthenticationPrincipal LandlordPrincipal principal, Model model) {
		long landlordId = principal.getLandlordId();
		model.addAttribute("settings", preferences.settings(landlordId));
		model.addAttribute("emailVerified",
				landlordService.findAccount(landlordId).map(a -> a.emailVerified() && a.email() != null).orElse(false));
		return "app/settings-notifications";
	}

	@PostMapping("/app/settings/notifications")
	public String save(@AuthenticationPrincipal LandlordPrincipal principal,
			@RequestParam(defaultValue = "false") boolean tenantReport,
			@RequestParam(defaultValue = "false") boolean overdueDigest,
			@RequestParam(defaultValue = "false") boolean leaseEndDigest,
			@RequestParam(defaultValue = "false") boolean tenantSms, RedirectAttributes redirect) {
		preferences.save(principal.getLandlordId(),
				new Settings(tenantReport, overdueDigest, leaseEndDigest, tenantSms));
		redirect.addFlashAttribute("notice", SAVED);
		return "redirect:/app/settings/notifications";
	}

}
