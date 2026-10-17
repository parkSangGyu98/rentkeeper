package com.rentkeeper.auth.signup;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;

import com.rentkeeper.auth.security.LandlordPrincipal;
import com.rentkeeper.auth.web.AuthenticatedSessionHelper;
import com.rentkeeper.landlord.application.DuplicateEmailException;
import com.rentkeeper.landlord.domain.EmailAddress.InvalidEmailException;
import com.rentkeeper.landlord.domain.Landlord.InvalidNameException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 이메일 회원가입 화면 (AUTH-1). 가입에 성공하면 바로 로그인해 내 화면(/app)으로 보낸다.
 * <p>
 * 오류 문구는 화면 계층인 여기서 고른다. 문구는 쉬운 말로 쓰고, 입력칸 바로 아래에 보여 준다.
 */
@Controller
public class SignupController {

	static final String VIEW = "auth/signup";

	private final SignupService signupService;

	private final AuthenticatedSessionHelper sessionHelper;

	public SignupController(SignupService signupService, AuthenticatedSessionHelper sessionHelper) {
		this.signupService = signupService;
		this.sessionHelper = sessionHelper;
	}

	@GetMapping("/signup")
	public String signupForm(Authentication authentication, Model model) {
		if (isLoggedIn(authentication)) {
			return "redirect:/app";
		}
		model.addAttribute("form", SignupForm.empty());
		return VIEW;
	}

	@PostMapping("/signup")
	public String signup(@Validated @ModelAttribute("form") SignupForm form, BindingResult result,
			HttpServletRequest request, HttpServletResponse response) {
		if (!result.hasFieldErrors("password") && !result.hasFieldErrors("passwordConfirm")
				&& !form.password().equals(form.passwordConfirm())) {
			result.rejectValue("passwordConfirm", "mismatch", "비밀번호가 서로 달라요. 같은 비밀번호를 두 번 입력해 주세요");
		}
		if (result.hasErrors()) {
			return VIEW;
		}

		LandlordPrincipal principal;
		try {
			principal = signupService.signup(form.email(), form.password(), form.name());
		} catch (InvalidEmailException e) {
			result.rejectValue("email", "invalid", "이메일 주소를 다시 확인해 주세요 (예: kim@example.com)");
			return VIEW;
		} catch (DuplicateEmailException e) {
			result.rejectValue("email", "duplicate", "이미 가입된 이메일이에요. 로그인해 주세요");
			return VIEW;
		} catch (WeakPasswordException e) {
			result.rejectValue("password", "weak", messageFor(e.getViolation()));
			return VIEW;
		} catch (InvalidNameException e) {
			result.rejectValue("name", "invalid", "이름(호칭)을 1~50자로 입력해 주세요");
			return VIEW;
		}

		sessionHelper.login(principal, request, response);
		return "redirect:/app";
	}

	static String messageFor(PasswordPolicy.Violation violation) {
		return switch (violation) {
			case TOO_SHORT -> "비밀번호는 8자 이상이어야 해요";
			case TOO_LONG -> "비밀번호가 너무 길어요 (영문·숫자 72자, 한글 24자까지)";
			case TOO_COMMON -> "너무 흔한 비밀번호예요. 다른 사람이 쉽게 짐작하기 어려운 비밀번호로 정해 주세요";
			case SAME_AS_EMAIL -> "이메일과 같은 비밀번호는 쓸 수 없어요";
			case REPEATED_CHARACTER -> "같은 글자만 반복한 비밀번호는 쓸 수 없어요";
		};
	}

	private static boolean isLoggedIn(Authentication authentication) {
		return authentication != null && authentication.isAuthenticated()
				&& !(authentication instanceof AnonymousAuthenticationToken);
	}

}
