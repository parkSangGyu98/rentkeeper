package com.rentkeeper.auth.settings;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.rentkeeper.auth.rememberme.HashingRememberMeServices;
import com.rentkeeper.auth.security.LandlordPrincipal;
import com.rentkeeper.auth.settings.PasswordChangeService.PasswordChangeException;
import com.rentkeeper.auth.signup.WeakPasswordException;
import com.rentkeeper.auth.web.PasswordMessages;
import com.rentkeeper.landlord.application.LandlordService;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 계정 설정과 비밀번호 바꾸기 (/app/settings). 이메일 바꾸기는 EmailChangeController(8b)가 맡는다.
 */
@Controller
public class AccountSettingsController {

	static final String SETTINGS_VIEW = "app/settings";

	static final String PASSWORD_VIEW = "app/settings-password";

	private final LandlordService landlordService;

	private final PasswordChangeService passwordChangeService;

	public AccountSettingsController(LandlordService landlordService, PasswordChangeService passwordChangeService) {
		this.landlordService = landlordService;
		this.passwordChangeService = passwordChangeService;
	}

	@GetMapping("/app/settings")
	public String settings(@AuthenticationPrincipal LandlordPrincipal principal, Model model) {
		model.addAttribute("account", landlordService.findAccount(principal.getLandlordId()).orElseThrow());
		return SETTINGS_VIEW;
	}

	@GetMapping("/app/settings/password")
	public String passwordForm() {
		return PASSWORD_VIEW;
	}

	@PostMapping("/app/settings/password")
	public String changePassword(@AuthenticationPrincipal LandlordPrincipal principal,
			@RequestParam(name = "currentPassword", defaultValue = "") String currentPassword,
			@RequestParam(name = "newPassword", defaultValue = "") String newPassword,
			@RequestParam(name = "newPasswordConfirm", defaultValue = "") String newPasswordConfirm, Model model,
			HttpServletRequest request, HttpServletResponse response, RedirectAttributes redirect) {
		if (!newPassword.equals(newPasswordConfirm)) {
			return withError(model, "새 비밀번호가 서로 달라요. 같은 비밀번호를 두 번 입력해 주세요");
		}
		try {
			passwordChangeService.change(principal.getLandlordId(), currentPassword, newPassword,
					request.getSession().getId());
		} catch (PasswordChangeException e) {
			return withError(model, switch (e.getFailure()) {
				case LOCKED -> "비밀번호를 여러 번 틀려서 잠시 막아 두었어요. 15분 뒤에 다시 시도해 주세요";
				case WRONG_CURRENT_PASSWORD -> "지금 쓰는 비밀번호가 맞지 않아요";
				case SAME_AS_CURRENT -> "지금 쓰는 비밀번호와 다른 비밀번호로 정해 주세요";
			});
		} catch (WeakPasswordException e) {
			return withError(model, PasswordMessages.forViolation(e.getViolation()));
		}

		// 지금 기기는 로그인 상태를 유지하되 세션 id는 바꾼다. 로그인 유지 쿠키는 DB에서 지웠으므로 브라우저에서도 지운다
		request.changeSessionId();
		response.addCookie(expiredRememberMeCookie(request));
		redirect.addFlashAttribute("notice", "비밀번호를 바꿨어요. 다른 기기에서는 모두 로그아웃됐어요.");
		return "redirect:/app/settings";
	}

	private static String withError(Model model, String error) {
		model.addAttribute("error", error);
		return PASSWORD_VIEW;
	}

	private static Cookie expiredRememberMeCookie(HttpServletRequest request) {
		Cookie cookie = new Cookie(HashingRememberMeServices.COOKIE_NAME, "");
		cookie.setMaxAge(0);
		cookie.setPath(request.getContextPath().isEmpty() ? "/" : request.getContextPath());
		cookie.setSecure(true);
		cookie.setHttpOnly(true);
		return cookie;
	}

}
