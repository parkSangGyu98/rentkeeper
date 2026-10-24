package com.rentkeeper.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.rentkeeper.TestcontainersConfiguration;
import com.rentkeeper.auth.security.LandlordPrincipal;
import com.rentkeeper.landlord.application.LandlordService;
import com.rentkeeper.landlord.domain.EmailAddress;
import com.rentkeeper.support.TestData;

import jakarta.servlet.http.Cookie;

/**
 * 보안 기본 구성: 접근 제어, CSRF, 보안 헤더, 로그인·로그아웃, 세션 (PRD 8.1, ADR-008).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SecurityTests {

	private static final String PASSWORD = "correct-horse-42";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	LandlordService landlordService;

	@Autowired
	PasswordEncoder passwordEncoder;

	@Autowired
	JdbcTemplate jdbcTemplate;

	long landlordId;

	@BeforeEach
	void setUp() {
		TestData.reset(jdbcTemplate);
		landlordId = landlordService.registerLocal(EmailAddress.of("kim@example.com"), passwordEncoder.encode(PASSWORD),
				"김임대");
	}

	// ---- 접근 제어 ----

	@Test
	void publicPagesAreOpenToEveryone() throws Exception {
		for (String path : new String[]{"/", "/login", "/terms", "/privacy", "/css/app.css", "/actuator/health"}) {
			mockMvc.perform(get(path)).andExpect(status().isOk());
		}
	}

	@Test
	void appRequiresLogin() throws Exception {
		mockMvc.perform(get("/app")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login"));
	}

	@Test
	void pathsNotOnAllowListAreDenied() throws Exception {
		// 기본 거부: 허용 목록 밖에서 처리할 컨트롤러가 없는 주소는 로그인 전·후 모두 404(막고 "페이지를 찾을 수 없어요")
		mockMvc.perform(get("/no-such-page")).andExpect(status().isNotFound());
		mockMvc.perform(get("/no-such-page").with(user(principal()))).andExpect(status().isNotFound());
		mockMvc.perform(get("/actuator/env").with(user(principal()))).andExpect(status().isNotFound());
		// 컨트롤러가 있는 주소는 권한 검사 그대로: 비로그인은 로그인 화면으로, 권한이 없으면 403
		mockMvc.perform(get("/signup/kakao")).andExpect(redirectedUrl("/login"));
		mockMvc.perform(get("/signup/kakao").with(user(principal()))).andExpect(status().isForbidden());
	}

	// ---- CSRF ----

	@Test
	void postWithoutCsrfTokenIsRejected() throws Exception {
		mockMvc.perform(post("/login").param("email", "kim@example.com").param("password", PASSWORD))
				.andExpect(status().isForbidden()).andExpect(unauthenticated());
		mockMvc.perform(post("/signup").param("email", "new@example.com")).andExpect(status().isForbidden());
		mockMvc.perform(post("/logout").with(user(principal()))).andExpect(status().isForbidden());
	}

	@Test
	void loginFormContainsCsrfToken() throws Exception {
		mockMvc.perform(get("/login")).andExpect(content().string(containsString("name=\"_csrf\"")));
	}

	// ---- 보안 헤더 ----

	@Test
	void securityHeadersArePresent() throws Exception {
		mockMvc.perform(get("/")).andExpect(header().string("Content-Security-Policy",
				"default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; font-src 'self'; "
						+ "form-action 'self'; frame-ancestors 'none'; base-uri 'self'; object-src 'none'"))
				.andExpect(header().string("X-Content-Type-Options", "nosniff"))
				.andExpect(header().string("X-Frame-Options", "DENY"))
				.andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"));
	}

	// ---- 로그인 ----

	@Test
	void loginSucceedsAndStoresSessionUnderLandlordId() throws Exception {
		mockMvc.perform(formLogin("/login").userParameter("email").user("kim@example.com").password(PASSWORD))
				.andExpect(redirectedUrl("/app"))
				.andExpect(authenticated().withUsername(String.valueOf(landlordId)).withRoles("LANDLORD"));

		// 세션 테이블의 principal 이름은 이메일이 아니라 landlord id (ADR-005)
		assertThat(jdbcTemplate.queryForList("select principal_name from spring_session", String.class))
				.containsExactly(String.valueOf(landlordId));
	}

	@Test
	void loginIgnoresEmailCase() throws Exception {
		mockMvc.perform(formLogin("/login").userParameter("email").user("  KIM@Example.COM ").password(PASSWORD))
				.andExpect(redirectedUrl("/app")).andExpect(authenticated());
	}

	@Test
	void loginChangesSessionId() throws Exception {
		// 세션 고정 공격 방지: 로그인 전 세션 id를 로그인 후에도 쓰면 안 된다
		MvcResult loginPage = mockMvc.perform(get("/login")).andReturn();
		Cookie before = loginPage.getResponse().getCookie("SESSION");
		assertThat(before).isNotNull();

		MvcResult login = mockMvc.perform(post("/login").cookie(before).with(csrf()).param("email", "kim@example.com")
				.param("password", PASSWORD)).andExpect(redirectedUrl("/app")).andReturn();
		Cookie after = login.getResponse().getCookie("SESSION");

		assertThat(after).isNotNull();
		assertThat(after.getValue()).isNotEqualTo(before.getValue());
	}

	@Test
	void wrongPasswordAndUnknownEmailShowSameError() throws Exception {
		mockMvc.perform(formLogin("/login").userParameter("email").user("kim@example.com").password("wrong-password"))
				.andExpect(redirectedUrl("/login?error")).andExpect(unauthenticated());
		mockMvc.perform(formLogin("/login").userParameter("email").user("nobody@example.com").password(PASSWORD))
				.andExpect(redirectedUrl("/login?error")).andExpect(unauthenticated());
		mockMvc.perform(formLogin("/login").userParameter("email").user("not-an-email").password(PASSWORD))
				.andExpect(redirectedUrl("/login?error")).andExpect(unauthenticated());

		mockMvc.perform(get("/login").param("error", ""))
				.andExpect(content().string(containsString("이메일 또는 비밀번호가 맞지 않아요")));
	}

	@Test
	void sessionDoesNotKeepPasswordHash() throws Exception {
		// 로그인 검증이 끝나면 비밀번호 해시를 지워, 세션(DB)에 저장되지 않게 한다
		mockMvc.perform(formLogin("/login").userParameter("email").user("kim@example.com").password(PASSWORD))
				.andExpect(authenticated().withAuthentication(authentication -> {
					LandlordPrincipal principal = (LandlordPrincipal) authentication.getPrincipal();
					assertThat(principal.getPassword()).isNull();
					assertThat(authentication.getCredentials()).isNull();
				}));
	}

	// ---- 로그인 후 화면과 로그아웃 ----

	@Test
	void appHomeShowsNameAndLogoutButton() throws Exception {
		// sec:authorize / sec:authentication(thymeleaf-extras-springsecurity6)이
		// Security 7에서 동작하는지 함께 확인한다
		mockMvc.perform(get("/app").with(user(principal()))).andExpect(status().isOk())
				.andExpect(content().string(containsString("김임대"))).andExpect(content().string(containsString("로그아웃")))
				.andExpect(content().string(not(containsString("가입하기"))));
		mockMvc.perform(get("/")).andExpect(content().string(containsString("가입하기")))
				.andExpect(content().string(not(containsString("로그아웃"))));
	}

	@Test
	void logoutEndsSessionAndRemovesItFromDatabase() throws Exception {
		MvcResult login = mockMvc
				.perform(formLogin("/login").userParameter("email").user("kim@example.com").password(PASSWORD))
				.andReturn();
		Cookie session = login.getResponse().getCookie("SESSION");
		assertThat(countSessionsOfLandlord()).isEqualTo(1);

		mockMvc.perform(post("/logout").cookie(session).with(csrf())).andExpect(redirectedUrl("/login?logout"));

		assertThat(countSessionsOfLandlord()).isZero();
		mockMvc.perform(get("/app").cookie(session)).andExpect(redirectedUrl("/login"));
	}

	@Test
	void loggedInUserVisitingLoginPageGoesToApp() throws Exception {
		mockMvc.perform(get("/login").with(user(principal()))).andExpect(redirectedUrl("/app"));
	}

	private LandlordPrincipal principal() {
		return new LandlordPrincipal(landlordId, "kim@example.com", "김임대", true, null);
	}

	private Integer countSessionsOfLandlord() {
		return jdbcTemplate.queryForObject("select count(*) from spring_session where principal_name = ?",
				Integer.class, String.valueOf(landlordId));
	}

}
