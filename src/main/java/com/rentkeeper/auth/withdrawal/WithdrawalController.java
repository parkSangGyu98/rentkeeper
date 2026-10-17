package com.rentkeeper.auth.withdrawal;

import java.time.Clock;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import com.rentkeeper.auth.security.LandlordPrincipal;
import com.rentkeeper.auth.web.AuthenticatedSessionHelper;
import com.rentkeeper.auth.withdrawal.WithdrawalService.WithdrawalException;
import com.rentkeeper.landlord.application.LandlordAccount;
import com.rentkeeper.landlord.application.LandlordService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 회원 탈퇴 화면 (/app/settings/withdraw, ADR-015).
 * <ul>
 * <li>이메일 가입 계정: 지금 비밀번호 + 확인 체크 → 바로 탈퇴</li>
 * <li>카카오 계정: 확인 체크(POST) → 10분 안에 카카오 재로그인 링크(GET) → 콜백에서 탈퇴
 * (KakaoWithdrawalCallback)</li>
 * </ul>
 * 카카오 재로그인 링크는 GET이다: CSP form-action이 카카오 주소로의 폼 전송을 막는다 (ADR-014).
 */
@Controller
public class WithdrawalController {

	static final String VIEW = "app/settings-withdraw";

	static final String WITHDRAWN_PATH = "/withdrawn";

	private static final String CONFIRM_REQUIRED = "탈퇴 안내를 읽고 확인란에 체크해 주세요";

	private final WithdrawalService withdrawalService;

	private final LandlordService landlordService;

	private final AuthenticatedSessionHelper sessionHelper;

	private final Clock clock;

	public WithdrawalController(WithdrawalService withdrawalService, LandlordService landlordService,
			AuthenticatedSessionHelper sessionHelper, Clock clock) {
		this.withdrawalService = withdrawalService;
		this.landlordService = landlordService;
		this.sessionHelper = sessionHelper;
		this.clock = clock;
	}

	@GetMapping("/app/settings/withdraw")
	public String form(@AuthenticationPrincipal LandlordPrincipal principal, HttpServletRequest request, Model model) {
		prepare(principal, request, model);
		// 값 없이 이름만 오는 표시용 파라미터 (?expired, ?kakaoError)
		if (request.getParameterMap().containsKey("expired")) {
			model.addAttribute("error", "확인 시간(10분)이 지났어요. 처음부터 다시 진행해 주세요");
		} else if (request.getParameterMap().containsKey("kakaoError")) {
			model.addAttribute("error", "카카오 계정 확인을 끝내지 못했어요. 탈퇴는 처리되지 않았어요");
		}
		return VIEW;
	}

	@PostMapping("/app/settings/withdraw")
	public String withdrawLocal(@AuthenticationPrincipal LandlordPrincipal principal,
			@RequestParam(name = "currentPassword", defaultValue = "") String currentPassword,
			@RequestParam(name = "confirm", defaultValue = "false") boolean confirm, HttpServletRequest request,
			HttpServletResponse response, Model model) {
		LandlordAccount account = prepare(principal, request, model);
		if (account.isKakao()) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND);
		}
		if (!confirm) {
			model.addAttribute("error", CONFIRM_REQUIRED);
			return VIEW;
		}
		try {
			withdrawalService.withdrawLocal(principal.getLandlordId(), currentPassword);
		} catch (WithdrawalException e) {
			model.addAttribute("error", switch (e.getFailure()) {
				case LOCKED -> "비밀번호를 여러 번 틀려서 잠시 막아 두었어요. 15분 뒤에 다시 시도해 주세요";
				case WRONG_PASSWORD -> "지금 쓰는 비밀번호가 맞지 않아요";
			});
			return VIEW;
		}
		sessionHelper.logout(request, response);
		return "redirect:" + WITHDRAWN_PATH;
	}

	/** 카카오 계정: 탈퇴 의사를 확인하고 카카오 재로그인 링크를 보여 준다 */
	@PostMapping("/app/settings/withdraw/kakao")
	public String confirmKakao(@AuthenticationPrincipal LandlordPrincipal principal,
			@RequestParam(name = "confirm", defaultValue = "false") boolean confirm, HttpServletRequest request,
			Model model) {
		LandlordAccount account = prepare(principal, request, model);
		if (!account.isKakao()) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND);
		}
		if (!confirm) {
			model.addAttribute("error", CONFIRM_REQUIRED);
			return VIEW;
		}
		new KakaoWithdrawalIntent(principal.getLandlordId(), clock.instant().plus(KakaoWithdrawalIntent.VALIDITY))
				.store(request);
		model.addAttribute("kakaoReauthReady", true);
		return VIEW;
	}

	@GetMapping(WITHDRAWN_PATH)
	public String withdrawn() {
		return "auth/withdrawn";
	}

	private LandlordAccount prepare(LandlordPrincipal principal, HttpServletRequest request, Model model) {
		LandlordAccount account = landlordService.findAccount(principal.getLandlordId()).orElseThrow();
		model.addAttribute("account", account);
		model.addAttribute("inAppBrowser", isKakaoTalkInAppBrowser(request));
		return account;
	}

	// 카카오톡 인앱 브라우저는 prompt=login(다시 로그인)을 지원하지 않는다 (카카오 문서)
	private static boolean isKakaoTalkInAppBrowser(HttpServletRequest request) {
		String userAgent = request.getHeader(HttpHeaders.USER_AGENT);
		return userAgent != null && userAgent.toUpperCase().contains("KAKAOTALK");
	}

}
