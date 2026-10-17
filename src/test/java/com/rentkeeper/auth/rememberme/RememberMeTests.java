package com.rentkeeper.auth.rememberme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.RememberMeAuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.rentkeeper.TestcontainersConfiguration;
import com.rentkeeper.auth.lock.LoginLockService;
import com.rentkeeper.common.crypto.SecureTokens;
import com.rentkeeper.landlord.application.LandlordService;
import com.rentkeeper.landlord.domain.EmailAddress;
import com.rentkeeper.support.TestData;

import jakarta.servlet.http.Cookie;

/**
 * 로그인 유지(remember-me 30일) (ADR-013): 해시 저장, 자동 로그인과 세션 저장, 토큰 회전, 재사용(도난) 처리,
 * 만료, 로그아웃, 잠금과의 관계, 정적 파일 체인.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RememberMeTests {

	private static final String EMAIL = "kim@example.com";

	private static final String PASSWORD = "Haengbok-Villa-2026";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	LandlordService landlordService;

	@Autowired
	PasswordEncoder passwordEncoder;

	@Autowired
	LoginLockService lockService;

	@Autowired
	HashingPersistentTokenRepository tokenRepository;

	long landlordId;

	@BeforeEach
	void setUp() {
		TestData.reset(jdbcTemplate);
		jdbcTemplate.update("delete from persistent_logins");
		landlordId = landlordService.registerLocal(EmailAddress.of(EMAIL), passwordEncoder.encode(PASSWORD), "김임대");
	}

	@Test
	void noCookieUnlessCheckboxIsChecked() throws Exception {
		MvcResult result = mockMvc
				.perform(post("/login").with(csrf()).param("email", EMAIL).param("password", PASSWORD)).andReturn();

		assertThat(result.getResponse().getCookie(HashingRememberMeServices.COOKIE_NAME)).isNull();
		assertThat(countTokens()).isZero();
	}

	@Test
	void checkedLoginIssuesSecureCookieAndStoresOnlyHash() throws Exception {
		Cookie cookie = loginWithRememberMe();

		assertThat(cookie.getMaxAge()).isEqualTo(30 * 24 * 60 * 60);
		assertThat(cookie.getSecure()).isTrue();
		assertThat(cookie.isHttpOnly()).isTrue();
		assertThat(cookie.getAttribute("SameSite")).isEqualTo("Lax");

		String[] seriesAndToken = decode(cookie);
		Map<String, Object> row = jdbcTemplate.queryForMap("select series, username, token from persistent_logins");
		assertThat(row.get("series")).isEqualTo(seriesAndToken[0]);
		assertThat(row.get("username")).isEqualTo(String.valueOf(landlordId));
		assertThat(row.get("token")).as("DB에는 해시만").isEqualTo(SecureTokens.sha256Hex(seriesAndToken[1]))
				.isNotEqualTo(seriesAndToken[1]);
	}

	@Test
	void autoLoginWithoutSessionCreatesSessionAndRotatesToken() throws Exception {
		Cookie original = loginWithRememberMe();
		jdbcTemplate.update("delete from spring_session"); // 세션 만료와 같은 상황

		MvcResult result = mockMvc.perform(get("/app").cookie(original)).andExpect(status().isOk()).andReturn();

		// 자동 로그인 결과가 세션(DB)에 저장된다 (계획에서 "확인 못함"이었던 항목)
		assertThat(jdbcTemplate.queryForList("select principal_name from spring_session", String.class))
				.containsExactly(String.valueOf(landlordId));
		assertThat(result.getResponse().getCookie("SESSION")).isNotNull();
		// 같은 기기(series)는 유지하고 토큰만 바뀐다
		Cookie rotated = result.getResponse().getCookie(HashingRememberMeServices.COOKIE_NAME);
		assertThat(decode(rotated)[0]).isEqualTo(decode(original)[0]);
		assertThat(decode(rotated)[1]).isNotEqualTo(decode(original)[1]);
		assertThat(jdbcTemplate.queryForObject("select token from persistent_logins", String.class))
				.isEqualTo(SecureTokens.sha256Hex(decode(rotated)[1]));
	}

	@Test
	void reusedOldTokenIsTreatedAsTheftWithoutServerError() throws Exception {
		Cookie original = loginWithRememberMe();
		jdbcTemplate.update("delete from spring_session");
		mockMvc.perform(get("/app").cookie(original)).andExpect(status().isOk()); // 회전됨

		// 이미 쓴 토큰을 다시 보냄 → 500이 아니라 로그아웃 상태 + 모든 토큰 삭제 + 로그인 화면 안내
		MvcResult reused = mockMvc.perform(get("/app").cookie(original)).andExpect(redirectedUrl("/login")).andReturn();

		assertThat(countTokens()).isZero();
		Cookie cleared = reused.getResponse().getCookie(HashingRememberMeServices.COOKIE_NAME);
		assertThat(cleared).isNotNull();
		assertThat(cleared.getMaxAge()).isZero();
		Cookie session = reused.getResponse().getCookie("SESSION");
		mockMvc.perform(get("/login").cookie(session))
				.andExpect(content().string(containsString("보안을 위해 이 기기의 로그인 유지를 해제했어요")));
		mockMvc.perform(get("/login").cookie(session))
				.andExpect(content().string(org.hamcrest.Matchers.not(containsString("로그인 유지를 해제했어요"))));
	}

	@Test
	void expiresAfterThirtyDays() throws Exception {
		Cookie cookie = loginWithRememberMe();
		jdbcTemplate.update("delete from spring_session");
		jdbcTemplate.update("update persistent_logins set last_used = now() - interval '30 days 1 minute'");

		mockMvc.perform(get("/app").cookie(cookie)).andExpect(redirectedUrl("/login"));
	}

	@Test
	void logoutRemovesAllTokensOfUser() throws Exception {
		Cookie first = loginWithRememberMe();
		loginWithRememberMe(); // 다른 기기
		assertThat(countTokens()).isEqualTo(2);

		MvcResult page = mockMvc.perform(get("/app").cookie(first)).andReturn();
		mockMvc.perform(post("/logout").with(csrf()).cookie(page.getResponse().getCookie("SESSION"),
				page.getResponse().getCookie(HashingRememberMeServices.COOKIE_NAME)))
				.andExpect(redirectedUrl("/login?logout"));

		assertThat(countTokens()).as("표준 동작: 모든 기기 해제").isZero();
	}

	@Test
	void lockedAccountCanStillAutoLogin() throws Exception {
		Cookie cookie = loginWithRememberMe();
		for (int i = 0; i < 5; i++) {
			lockService.recordFailure(landlordId);
		}
		assertThat(lockService.isLocked(landlordId)).isTrue();
		jdbcTemplate.update("delete from spring_session");

		mockMvc.perform(get("/app").cookie(cookie)).andExpect(status().isOk());
	}

	@Test
	void staticResourcesDoNotTriggerAutoLogin() throws Exception {
		Cookie cookie = loginWithRememberMe();
		jdbcTemplate.update("delete from spring_session");
		String tokenBefore = jdbcTemplate.queryForObject("select token from persistent_logins", String.class);

		MvcResult css = mockMvc.perform(get("/css/app.css").cookie(cookie)).andExpect(status().isOk()).andReturn();

		assertThat(css.getResponse().getCookie(HashingRememberMeServices.COOKIE_NAME)).isNull();
		assertThat(css.getResponse().getHeader("Content-Security-Policy")).as("정적 파일에도 보안 헤더").isNotNull();
		assertThat(jdbcTemplate.queryForObject("select token from persistent_logins", String.class))
				.isEqualTo(tokenBefore);
		assertThat(jdbcTemplate.queryForObject("select count(*) from spring_session", Integer.class)).isZero();
	}

	@Test
	void changingKeyDoesNotInvalidateExistingPersistentLogins() {
		// 키는 자동 로그인 때 새로 만드는 인증 객체에만 쓰이고, DB 토큰과는 무관하다 (ADR-013, .env.example 주석)
		RememberMeUserDetailsService users = new RememberMeUserDetailsService(landlordService);
		HashingRememberMeServices oldKey = new HashingRememberMeServices("a".repeat(40), users, tokenRepository);
		HashingRememberMeServices newKey = new HashingRememberMeServices("b".repeat(40), users, tokenRepository);

		MockHttpServletRequest loginRequest = new MockHttpServletRequest();
		loginRequest.setParameter(HashingRememberMeServices.PARAMETER, "on");
		MockHttpServletResponse loginResponse = new MockHttpServletResponse();
		Authentication login = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
				String.valueOf(landlordId), null, java.util.List.of());
		oldKey.loginSuccess(loginRequest, loginResponse, login);

		MockHttpServletRequest next = new MockHttpServletRequest();
		next.setCookies(loginResponse.getCookie(HashingRememberMeServices.COOKIE_NAME));
		Authentication auto = newKey.autoLogin(next, new MockHttpServletResponse());

		assertThat(auto).isNotNull();
		assertThat(new RememberMeAuthenticationProvider("b".repeat(40)).authenticate(auto).isAuthenticated()).isTrue();
	}

	private Cookie loginWithRememberMe() throws Exception {
		MvcResult result = mockMvc.perform(post("/login").with(csrf()).param("email", EMAIL).param("password", PASSWORD)
				.param(HashingRememberMeServices.PARAMETER, "on")).andExpect(redirectedUrl("/app")).andReturn();
		Cookie cookie = result.getResponse().getCookie(HashingRememberMeServices.COOKIE_NAME);
		assertThat(cookie).isNotNull();
		return cookie;
	}

	// Spring Security는 series·token을 각각 URL 인코딩해 ':'로 잇고 Base64로 감싼다
	// (AbstractRememberMeServices.encodeCookie)
	private static String[] decode(Cookie cookie) {
		String[] parts = new String(Base64.getDecoder().decode(cookie.getValue()), StandardCharsets.UTF_8).split(":");
		for (int i = 0; i < parts.length; i++) {
			parts[i] = URLDecoder.decode(parts[i], StandardCharsets.UTF_8);
		}
		return parts;
	}

	private Integer countTokens() {
		return jdbcTemplate.queryForObject("select count(*) from persistent_logins", Integer.class);
	}

}
