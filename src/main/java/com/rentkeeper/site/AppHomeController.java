package com.rentkeeper.site;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 로그인 후 첫 화면(임시). M4에서 대시보드(/app/dashboard)가 생기면 그쪽으로 옮긴다. 사용자 이름은 템플릿이
 * sec:authentication으로 직접 읽으므로 이 컨트롤러는 auth 모듈에 의존하지 않는다.
 */
@Controller
public class AppHomeController {

	@GetMapping("/app")
	public String home() {
		return "app/home";
	}

}
