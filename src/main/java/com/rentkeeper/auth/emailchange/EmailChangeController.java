package com.rentkeeper.auth.emailchange;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.rentkeeper.auth.emailchange.EmailChangeService.EmailChangeException;
import com.rentkeeper.auth.security.LandlordPrincipal;
import com.rentkeeper.auth.settings.LocalAccountGuard;
import com.rentkeeper.landlord.application.DuplicateEmailException;

import jakarta.servlet.http.HttpServletResponse;

/**
 * 이메일 바꾸기 화면 (/app/settings/email)과 새 주소의 확인 링크 화면 (/verify-email-change).
 * <p>
 * 확인 링크는 다른 기기에서 열 수 있으므로 로그인하지 않아도 된다. 링크를 열면(GET) 확인 화면만, 버튼(POST)을 눌러야 바뀐다
 * (ADR-011).
 */
@Controller
public class EmailChangeController {

	static final String FORM_VIEW = "app/settings-email";

	static final String CONFIRM_VIEW = "auth/verify-email-change";

	private final EmailChangeService emailChangeService;

	private final LocalAccountGuard localAccountGuard;

	public EmailChangeController(EmailChangeService emailChangeService, LocalAccountGuard localAccountGuard) {
		this.emailChangeService = emailChangeService;
		this.localAccountGuard = localAccountGuard;
	}

	@GetMapping("/app/settings/email")
	public String form(@AuthenticationPrincipal LandlordPrincipal principal, Model model) {
		localAccountGuard.requireLocal(principal.getLandlordId());
		model.addAttribute("state", "FORM");
		return FORM_VIEW;
	}

	@PostMapping("/app/settings/email")
	public String request(@AuthenticationPrincipal LandlordPrincipal principal,
			@RequestParam(name = "currentPassword", defaultValue = "") String currentPassword,
			@RequestParam(name = "newEmail", defaultValue = "") String newEmail, Model model) {
		localAccountGuard.requireLocal(principal.getLandlordId());
		try {
			emailChangeService.requestChange(principal.getLandlordId(), currentPassword, newEmail);
		} catch (EmailChangeException e) {
			model.addAttribute("state", "FORM");
			model.addAttribute("newEmail", newEmail);
			model.addAttribute("error", switch (e.getFailure()) {
				case LOCKED -> "비밀번호를 여러 번 틀려서 잠시 막아 두었어요. 15분 뒤에 다시 시도해 주세요";
				case WRONG_CURRENT_PASSWORD -> "지금 쓰는 비밀번호가 맞지 않아요";
				case INVALID_EMAIL -> "새 이메일 주소를 다시 확인해 주세요 (예: kim@example.com)";
				case SAME_AS_CURRENT -> "지금 쓰는 이메일과 같아요";
				case ALREADY_TAKEN -> "이미 다른 계정에서 쓰는 이메일이에요";
			});
			return FORM_VIEW;
		}
		model.addAttribute("state", "SENT");
		return FORM_VIEW;
	}

	@GetMapping("/verify-email-change")
	public String confirmPage(@RequestParam(name = "token", required = false) String token, Model model,
			HttpServletResponse response) {
		noReferrer(response);
		if (!emailChangeService.isUsable(token)) {
			model.addAttribute("state", "INVALID");
			return CONFIRM_VIEW;
		}
		model.addAttribute("state", "CONFIRM");
		model.addAttribute("token", token);
		return CONFIRM_VIEW;
	}

	@PostMapping("/verify-email-change")
	public String confirm(@RequestParam(name = "token", required = false) String token, Model model,
			HttpServletResponse response) {
		noReferrer(response);
		String state;
		try {
			state = emailChangeService.confirm(token) ? "DONE" : "INVALID";
		} catch (DuplicateEmailException e) {
			state = "DUPLICATE";
		}
		model.addAttribute("state", state);
		return CONFIRM_VIEW;
	}

	// 주소창에 토큰이 있으므로 다른 사이트로 이동할 때 주소가 전달되지 않게 한다
	private static void noReferrer(HttpServletResponse response) {
		response.setHeader("Referrer-Policy", "no-referrer");
	}

}
