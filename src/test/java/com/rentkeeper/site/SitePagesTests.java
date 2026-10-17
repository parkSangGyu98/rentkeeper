package com.rentkeeper.site;

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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.rentkeeper.TestcontainersConfiguration;

import jakarta.servlet.RequestDispatcher;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SitePagesTests {

	@Autowired
	MockMvc mockMvc;

	@Test
	void landingPageRendersWithLayout() throws Exception {
		mockMvc.perform(get("/")).andExpect(status().isOk()).andExpect(content().string(containsString("이제 엑셀 없이")))
				.andExpect(content().string(containsString("/css/app.css")))
				.andExpect(content().string(containsString("본문 바로가기")));
	}

	@Test
	void termsAndPrivacyShowDraftNoticeAndVersion() throws Exception {
		for (String path : new String[]{"/terms", "/privacy"}) {
			mockMvc.perform(get(path)).andExpect(status().isOk())
					.andExpect(content().string(containsString("초안: 출시 전 전문가 검토 필요")))
					.andExpect(content().string(containsString("v0.1")))
					.andExpect(content().string(containsString("시행일 미정")));
		}
	}

	@Test
	void builtCssIsServed() throws Exception {
		mockMvc.perform(get("/css/app.css")).andExpect(status().isOk())
				.andExpect(content().string(containsString("tailwindcss")));
	}

	@Test
	void missingStaticResourceIsNotFound() throws Exception {
		mockMvc.perform(get("/css/no-such.css")).andExpect(status().isNotFound());
	}

	// MockMvc는 서블릿 컨테이너의 오류 전달(/error)을 하지 않으므로, 컨테이너가 하듯 오류 정보를 담아 /error를 직접 호출한다
	@Test
	void notFoundErrorPageIsFriendly() throws Exception {
		mockMvc.perform(get("/error").accept(MediaType.TEXT_HTML).requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 404)
				.requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/no-such-page")).andExpect(status().isNotFound())
				.andExpect(content().string(containsString("페이지를 찾을 수 없어요")));
	}

	@Test
	void serverErrorPageHidesInternalDetails() throws Exception {
		mockMvc.perform(get("/error").accept(MediaType.TEXT_HTML).requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 500)
				.requestAttr(RequestDispatcher.ERROR_EXCEPTION, new IllegalStateException("secret-internal-detail"))
				.requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/boom")).andExpect(status().isInternalServerError())
				.andExpect(content().string(containsString("잠시 문제가 생겼어요")))
				.andExpect(content().string(not(containsString("secret-internal-detail"))))
				.andExpect(content().string(not(containsString("IllegalStateException"))))
				.andExpect(content().string(not(containsString("at com.rentkeeper"))));
	}

}
