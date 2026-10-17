package com.rentkeeper.auth.kakao;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.rentkeeper.auth.security.LandlordPrincipal;
import com.rentkeeper.common.ratelimit.RateLimitRule;
import com.rentkeeper.common.ratelimit.RateLimiter;
import com.rentkeeper.landlord.application.LandlordService;
import com.rentkeeper.landlord.domain.EmailAddress;
import com.rentkeeper.landlord.domain.EmailAddress.InvalidEmailException;

/**
 * 카카오 계정의 알림 받을 이메일 넣기·고치기 (/app/settings/notification-email). 이메일이 없거나 인증 전인
 * 카카오 계정만 쓸 수 있고, 그 밖에는 404.
 */
@Controller
public class NotificationEmailController {

	static final String VIEW = "app/settings-notification-email";

	private final NotificationEmailService notificationEmailService;

	private final LandlordService landlordService;

	private final RateLimiter rateLimiter;

	public NotificationEmailController(NotificationEmailService notificationEmailService,
			LandlordService landlordService, RateLimiter rateLimiter) {
		this.notificationEmailService = notificationEmailService;
		this.landlordService = landlordService;
		this.rateLimiter = rateLimiter;
	}

	@GetMapping("/app/settings/notification-email")
	public String form(@AuthenticationPrincipal LandlordPrincipal principal, Model model) {
		requireEditable(principal);
		String current = landlordService.findAccount(principal.getLandlordId()).orElseThrow().email();
		model.addAttribute("email", current == null ? "" : current);
		return VIEW;
	}

	@PostMapping("/app/settings/notification-email")
	public String save(@AuthenticationPrincipal LandlordPrincipal principal,
			@RequestParam(name = "email", defaultValue = "") String email, Model model, RedirectAttributes redirect) {
		requireEditable(principal);
		model.addAttribute("email", email);
		EmailAddress address;
		try {
			address = EmailAddress.of(email);
		} catch (InvalidEmailException e) {
			model.addAttribute("error", "이메일 주소를 다시 확인해 주세요 (예: kim@example.com)");
			return VIEW;
		}
		// 인증 메일을 보내므로 "다시 받기"와 같은 횟수 제한을 쓴다 (주소를 바꿔 가며 메일을 마구 보내지 못하게)
		if (!rateLimiter.tryAcquire(RateLimitRule.VERIFICATION_RESEND_PER_ACCOUNT,
				String.valueOf(principal.getLandlordId()))) {
			model.addAttribute("error", "인증 메일은 1시간에 3번까지 받을 수 있어요. 잠시 후 다시 시도해 주세요");
			return VIEW;
		}
		notificationEmailService.set(principal.getLandlordId(), address);
		redirect.addFlashAttribute("notice", "인증 메일을 보냈어요. 메일함(스팸함 포함)에서 링크를 눌러 주세요.");
		return "redirect:/app/settings";
	}

	private void requireEditable(LandlordPrincipal principal) {
		if (!notificationEmailService.isEditable(principal.getLandlordId())) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND);
		}
	}

}
