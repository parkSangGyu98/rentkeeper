package com.rentkeeper.auth.reset;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.rentkeeper.auth.signup.WeakPasswordException;
import com.rentkeeper.auth.web.PasswordMessages;
import com.rentkeeper.common.ratelimit.RateLimitRule;
import com.rentkeeper.common.ratelimit.RateLimiter;
import com.rentkeeper.common.web.ClientIp;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 비밀번호 재설정 화면 (AUTH-3).
 * <ol>
 * <li>/password-reset: 이메일 입력 → 가입 여부와 관계없이 항상 같은 안내</li>
 * <li>메일의 /password-reset/confirm?token=...: 링크를 열면(GET) 새 비밀번호 입력 화면만,
 * 제출(POST)해야 토큰을 쓴다 (ADR-011)</li>
 * <li>성공하면 로그인 화면으로 (자동 로그인 없음, M1-b 결정 4)</li>
 * </ol>
 */
@Controller
public class PasswordResetController {

	static final String REQUEST_VIEW = "auth/password-reset";

	static final String CONFIRM_VIEW = "auth/password-reset-confirm";

	private final PasswordResetService resetService;

	private final RateLimiter rateLimiter;

	public PasswordResetController(PasswordResetService resetService, RateLimiter rateLimiter) {
		this.resetService = resetService;
		this.rateLimiter = rateLimiter;
	}

	@GetMapping("/password-reset")
	public String requestForm(Model model) {
		model.addAttribute("state", "FORM");
		return REQUEST_VIEW;
	}

	@PostMapping("/password-reset")
	public String request(@RequestParam(name = "email", required = false) String email, Model model,
			HttpServletRequest request) {
		rateLimiter.acquireOrThrow(RateLimitRule.PASSWORD_RESET_REQUEST_PER_IP, ClientIp.of(request));
		resetService.requestReset(email);
		// 가입된 이메일인지 알려 주지 않는다: 어떤 경우든 같은 화면
		model.addAttribute("state", "SENT");
		return REQUEST_VIEW;
	}

	@GetMapping("/password-reset/confirm")
	public String confirmForm(@RequestParam(name = "token", required = false) String token, Model model,
			HttpServletResponse response) {
		noReferrer(response);
		if (!resetService.isUsable(token)) {
			model.addAttribute("state", "INVALID");
			return CONFIRM_VIEW;
		}
		model.addAttribute("state", "FORM");
		model.addAttribute("token", token);
		return CONFIRM_VIEW;
	}

	@PostMapping("/password-reset/confirm")
	public String confirm(@RequestParam(name = "token", required = false) String token,
			@RequestParam(name = "password", defaultValue = "") String password,
			@RequestParam(name = "passwordConfirm", defaultValue = "") String passwordConfirm, Model model,
			HttpServletRequest request, HttpServletResponse response) {
		noReferrer(response);
		rateLimiter.acquireOrThrow(RateLimitRule.PASSWORD_RESET_CONFIRM_PER_IP, ClientIp.of(request));
		if (!password.equals(passwordConfirm)) {
			return formWithError(model, token, "비밀번호가 서로 달라요. 같은 비밀번호를 두 번 입력해 주세요");
		}
		try {
			if (!resetService.reset(token, password)) {
				model.addAttribute("state", "INVALID");
				return CONFIRM_VIEW;
			}
		} catch (WeakPasswordException e) {
			return formWithError(model, token, PasswordMessages.forViolation(e.getViolation()));
		}
		return "redirect:/login?reset";
	}

	private static String formWithError(Model model, String token, String error) {
		model.addAttribute("state", "FORM");
		model.addAttribute("token", token);
		model.addAttribute("passwordError", error);
		return CONFIRM_VIEW;
	}

	// 주소창에 토큰이 있으므로 다른 사이트로 이동할 때 주소가 전달되지 않게 한다
	private static void noReferrer(HttpServletResponse response) {
		response.setHeader("Referrer-Policy", "no-referrer");
	}

}
