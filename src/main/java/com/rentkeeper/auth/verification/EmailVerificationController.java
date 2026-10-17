package com.rentkeeper.auth.verification;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.rentkeeper.auth.security.LandlordPrincipal;

import jakarta.servlet.http.HttpServletResponse;

/**
 * 이메일 인증 링크 화면. 링크를 열면(GET) 확인 화면만 보여 주고, [인증 완료하기]를 눌러야(POST) 인증된다 (ADR-011).
 * 다른 기기에서 링크를 열 수 있으므로 로그인하지 않아도 된다.
 */
@Controller
public class EmailVerificationController {

	static final String VIEW = "auth/verify-email";

	private final EmailVerificationService verificationService;

	public EmailVerificationController(EmailVerificationService verificationService) {
		this.verificationService = verificationService;
	}

	@GetMapping("/verify-email")
	public String confirm(@RequestParam(name = "token", required = false) String token, Model model,
			HttpServletResponse response) {
		noReferrer(response);
		if (!verificationService.isUsable(token)) {
			model.addAttribute("state", "INVALID");
			return VIEW;
		}
		model.addAttribute("state", "CONFIRM");
		model.addAttribute("token", token);
		return VIEW;
	}

	@PostMapping("/verify-email")
	public String verify(@RequestParam(name = "token", required = false) String token, Model model,
			HttpServletResponse response) {
		noReferrer(response);
		model.addAttribute("state", verificationService.verify(token) ? "DONE" : "INVALID");
		return VIEW;
	}

	@PostMapping("/app/email-verification/resend")
	public String resend(@AuthenticationPrincipal LandlordPrincipal principal, RedirectAttributes redirect) {
		if (verificationService.sendVerification(principal.getLandlordId())) {
			redirect.addFlashAttribute("notice", "인증 메일을 다시 보냈어요. 메일함(스팸함 포함)을 확인해 주세요.");
		}
		return "redirect:/app";
	}

	// 주소창에 토큰이 있으므로 다른 사이트로 이동할 때 주소가 전달되지 않게 한다
	private static void noReferrer(HttpServletResponse response) {
		response.setHeader("Referrer-Policy", "no-referrer");
	}

}
