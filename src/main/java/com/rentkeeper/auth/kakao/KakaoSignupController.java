package com.rentkeeper.auth.kakao;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;

import com.rentkeeper.auth.security.LandlordPrincipal;
import com.rentkeeper.auth.web.AuthenticatedSessionHelper;
import com.rentkeeper.landlord.application.DuplicateKakaoAccountException;
import com.rentkeeper.landlord.domain.EmailAddress.InvalidEmailException;
import com.rentkeeper.landlord.domain.Landlord.InvalidNameException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 카카오 가입 단계 (/signup/kakao). 카카오로 처음 온 사람(ROLE_KAKAO_SIGNUP)만 들어올 수 있다.
 * <p>
 * 약관 동의(필수), 이름(닉네임으로 미리 채움), 알림 받을 이메일(선택)을 받고 가입을 마치면 정식 로그인으로 바꾼다.
 */
@Controller
public class KakaoSignupController {

	static final String VIEW = "auth/signup-kakao";

	private final KakaoSignupService signupService;

	private final AuthenticatedSessionHelper sessionHelper;

	public KakaoSignupController(KakaoSignupService signupService, AuthenticatedSessionHelper sessionHelper) {
		this.signupService = signupService;
		this.sessionHelper = sessionHelper;
	}

	@GetMapping("/signup/kakao")
	public String form(@AuthenticationPrincipal KakaoSignupPrincipal pending, Model model) {
		model.addAttribute("form", KakaoSignupForm.prefilled(pending.profile()));
		return VIEW;
	}

	@PostMapping("/signup/kakao")
	public String complete(@AuthenticationPrincipal KakaoSignupPrincipal pending,
			@Validated @ModelAttribute("form") KakaoSignupForm form, BindingResult result, Model model,
			HttpServletRequest request, HttpServletResponse response) {
		if (result.hasErrors()) {
			return VIEW;
		}
		LandlordPrincipal principal;
		try {
			principal = signupService.complete(pending.profile(), form.name(), form.notificationEmail());
		} catch (InvalidEmailException e) {
			result.rejectValue("notificationEmail", "invalid", "이메일 주소를 다시 확인해 주세요 (예: kim@example.com)");
			return VIEW;
		} catch (InvalidNameException e) {
			result.rejectValue("name", "invalid", "이름(호칭)을 1~50자로 입력해 주세요");
			return VIEW;
		} catch (DuplicateKakaoAccountException e) {
			model.addAttribute("alreadyRegistered", true);
			return VIEW;
		}
		// 임시 로그인 정보를 정식 로그인으로 바꾼다 (세션 id도 바뀐다)
		sessionHelper.login(principal, request, response);
		return "redirect:/app";
	}

}
