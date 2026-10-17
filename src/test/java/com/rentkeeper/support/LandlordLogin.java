package com.rentkeeper.support;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import com.rentkeeper.landlord.application.LandlordService;
import com.rentkeeper.landlord.domain.EmailAddress;

import jakarta.servlet.http.Cookie;

/**
 * 웹 테스트용: 이메일 임대인을 만들고 로그인한 세션 쿠키를 얻는다. 여러 임대인(A·B)으로 소유권(IDOR)을 시험할 때 쓴다.
 */
public final class LandlordLogin {

	public static final String PASSWORD = "Haengbok-Villa-2026";

	private LandlordLogin() {
	}

	public record Session(long landlordId, Cookie cookie) {
	}

	public static Session signUpAndLogin(MockMvc mockMvc, LandlordService landlordService,
			PasswordEncoder passwordEncoder, String email) throws Exception {
		long id = landlordService.registerLocal(EmailAddress.of(email), passwordEncoder.encode(PASSWORD), "임대인");
		Cookie cookie = mockMvc.perform(post("/login").with(csrf()).param("email", email).param("password", PASSWORD))
				.andExpect(redirectedUrl("/app")).andReturn().getResponse().getCookie("SESSION");
		return new Session(id, cookie);
	}

}
