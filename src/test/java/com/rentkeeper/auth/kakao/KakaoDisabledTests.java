package com.rentkeeper.auth.kakao;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import com.rentkeeper.TestcontainersConfiguration;

/**
 * 카카오 키가 없으면(local·CI) 앱은 정상 기동하고 카카오 로그인만 꺼진다 (M1-c 결정 10). 로컬 .env의 실제 키를 빈
 * 값으로 덮어써 키가 없는 상황을 만든다.
 */
@SpringBootTest(properties = {"app.kakao.client-id=", "app.kakao.client-secret=", "app.kakao.admin-key="})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class KakaoDisabledTests {

	@Autowired
	MockMvc mockMvc;

	@Test
	void appStartsAndHidesKakaoButton() throws Exception {
		mockMvc.perform(get("/login")).andExpect(status().isOk())
				.andExpect(content().string(not(containsString("카카오로 시작하기"))));
		mockMvc.perform(get("/signup")).andExpect(content().string(not(containsString("카카오로 시작하기"))));
	}

	@Test
	void kakaoAuthorizationEndpointIsNotActive() throws Exception {
		// 등록이 없으니 Spring Security가 처리하지 않는다 → 처리할 컨트롤러도 없어 404
		mockMvc.perform(get("/oauth2/authorization/kakao")).andExpect(status().isNotFound());
	}

}
