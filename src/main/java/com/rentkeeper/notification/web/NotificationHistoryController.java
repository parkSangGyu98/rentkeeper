package com.rentkeeper.notification.web;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.rentkeeper.auth.security.LandlordPrincipal;
import com.rentkeeper.notification.application.NotificationHistory;

/** 알림 이력 (NOTI-7). 로그인한 임대인의 것만 보인다(남의 ID를 받지 않는다) */
@Controller
public class NotificationHistoryController {

	private final NotificationHistory history;

	public NotificationHistoryController(NotificationHistory history) {
		this.history = history;
	}

	@GetMapping("/app/notifications")
	public String list(@AuthenticationPrincipal LandlordPrincipal principal, @RequestParam(defaultValue = "0") int page,
			Model model) {
		model.addAttribute("history", history.page(principal.getLandlordId(), page));
		return "app/notifications";
	}

}
