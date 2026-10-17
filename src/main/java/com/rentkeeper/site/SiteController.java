package com.rentkeeper.site;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 로그인 없이 볼 수 있는 공개 페이지: 랜딩, 이용약관, 개인정보처리방침.
 */
@Controller
public class SiteController {

	@GetMapping("/")
	public String landing() {
		return "site/landing";
	}

	@GetMapping("/terms")
	public String terms(Model model) {
		model.addAttribute("document", LegalDocument.TERMS);
		return "site/terms";
	}

	@GetMapping("/privacy")
	public String privacy(Model model) {
		model.addAttribute("document", LegalDocument.PRIVACY);
		return "site/privacy";
	}

}
