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

	static final String DEMO_NO_SMS = "알림 설정을 저장했어요. 데모 계정에서는 세입자 안내 문자를 켤 수 없어요.";

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
		model.addAttribute("demo", isDemo(landlordId));
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
		// 데모 계정은 세입자 안내 문자를 켤 수 없다 (M6 결정 3 보강): 보내도 꺼진 채로 저장한다
		boolean demo = isDemo(principal.getLandlordId());
		preferences.save(principal.getLandlordId(),
				new Settings(tenantReport, overdueDigest, leaseEndDigest, tenantSms && !demo));
		redirect.addFlashAttribute("notice", demo && tenantSms ? DEMO_NO_SMS : SAVED);
		return "redirect:/app/settings/notifications";
	}

	private boolean isDemo(long landlordId) {
		return landlordService.findAccount(landlordId).map(a -> a.demo()).orElse(false);
	}

}
