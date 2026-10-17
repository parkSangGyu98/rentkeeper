package com.rentkeeper.auth.signup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.rentkeeper.TestcontainersConfiguration;
import com.rentkeeper.auth.security.LandlordPrincipal;
import com.rentkeeper.support.TestData;

/**
 * 이메일 회원가입 (AUTH-1): 성공, 중복 이메일, 약한 비밀번호, 입력 오류, 평문 비밀번호 미저장.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SignupTests {

	private static final String PASSWORD = "Haengbok-Villa-2026";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	PasswordEncoder passwordEncoder;

	@BeforeEach
	void cleanUp() {
		TestData.reset(jdbcTemplate);
	}

	@Test
	void signupFormRenders() throws Exception {
		mockMvc.perform(get("/signup")).andExpect(status().isOk()).andExpect(content().string(containsString("이름(호칭)")))
				.andExpect(content().string(containsString("name=\"_csrf\"")));
	}

	@Test
	void signupCreatesAccountAndLogsIn() throws Exception {
		mockMvc.perform(signup("  Kim@Example.COM ", PASSWORD, PASSWORD, " 김임대 ", true))
				.andExpect(redirectedUrl("/app"))
				.andExpect(authenticated().withRoles("LANDLORD").withAuthentication(authentication -> {
					LandlordPrincipal principal = (LandlordPrincipal) authentication.getPrincipal();
					assertThat(principal.getEmail()).isEqualTo("kim@example.com");
					assertThat(principal.getDisplayName()).isEqualTo("김임대");
				}));

		Map<String, Object> row = jdbcTemplate.queryForMap("select id, email, password_hash, name from landlord");
		assertThat(row.get("email")).isEqualTo("kim@example.com");
		assertThat(row.get("name")).isEqualTo("김임대");

		// 바로 로그인: 세션이 landlord id로 저장된다
		assertThat(jdbcTemplate.queryForList("select principal_name from spring_session", String.class))
				.containsExactly(String.valueOf(row.get("id")));
	}

	@Test
	void passwordIsStoredAsBcryptHashNotPlainText() throws Exception {
		mockMvc.perform(signup("kim@example.com", PASSWORD, PASSWORD, "김임대", true)).andExpect(redirectedUrl("/app"));

		String hash = jdbcTemplate.queryForObject("select password_hash from landlord", String.class);
		assertThat(hash).startsWith("{bcrypt}$2a$12$").doesNotContain(PASSWORD);
		assertThat(passwordEncoder.matches(PASSWORD, hash)).isTrue();
	}

	@Test
	void duplicateEmailIgnoringCaseIsRejected() throws Exception {
		mockMvc.perform(signup("kim@example.com", PASSWORD, PASSWORD, "김임대", true)).andExpect(redirectedUrl("/app"));

		mockMvc.perform(signup("KIM@Example.com", "Another-Pass-77", "Another-Pass-77", "다른사람", true))
				.andExpect(status().isOk()).andExpect(unauthenticated())
				.andExpect(content().string(containsString("이미 가입된 이메일이에요")));

		assertThat(countLandlords()).isEqualTo(1);
	}

	@Test
	void weakPasswordIsRejected() throws Exception {
		mockMvc.perform(signup("kim@example.com", "password1", "password1", "김임대", true)).andExpect(status().isOk())
				.andExpect(content().string(containsString("너무 흔한 비밀번호예요")));
		mockMvc.perform(signup("kim@example.com", "short1", "short1", "김임대", true)).andExpect(status().isOk())
				.andExpect(content().string(containsString("8자 이상")));
		mockMvc.perform(signup("kim@example.com", "kim@example.com", "kim@example.com", "김임대", true))
				.andExpect(status().isOk()).andExpect(content().string(containsString("이메일과 같은 비밀번호는 쓸 수 없어요")));

		assertThat(countLandlords()).isZero();
	}

	@Test
	void mismatchedConfirmationIsRejected() throws Exception {
		mockMvc.perform(signup("kim@example.com", PASSWORD, PASSWORD + "x", "김임대", true)).andExpect(status().isOk())
				.andExpect(content().string(containsString("비밀번호가 서로 달라요")));
		assertThat(countLandlords()).isZero();
	}

	@Test
	void agreementIsRequired() throws Exception {
		mockMvc.perform(signup("kim@example.com", PASSWORD, PASSWORD, "김임대", false)).andExpect(status().isOk())
				.andExpect(content().string(containsString("이용약관과 개인정보처리방침에 동의해 주세요")));
		assertThat(countLandlords()).isZero();
	}

	@Test
	void invalidEmailAndBlankNameAreRejected() throws Exception {
		mockMvc.perform(signup("not-an-email", PASSWORD, PASSWORD, "김임대", true)).andExpect(status().isOk())
				.andExpect(content().string(containsString("이메일 주소를 다시 확인해 주세요")));
		mockMvc.perform(signup("kim@example.com", PASSWORD, PASSWORD, "   ", true)).andExpect(status().isOk())
				.andExpect(content().string(containsString("이름(호칭)을 입력해 주세요")));
		assertThat(countLandlords()).isZero();
	}

	@Test
	void passwordIsNotEchoedBackOnError() throws Exception {
		mockMvc.perform(signup("kim@example.com", PASSWORD, PASSWORD, "김임대", false))
				.andExpect(content().string(not(containsString(PASSWORD))))
				.andExpect(content().string(containsString("kim@example.com")));
	}

	@Test
	void loggedInUserVisitingSignupGoesToApp() throws Exception {
		mockMvc.perform(get("/signup").with(user(new LandlordPrincipal(1L, "kim@example.com", "김임대", true, null))))
				.andExpect(redirectedUrl("/app"));
	}

	private MockHttpServletRequestBuilder signup(String email, String password, String confirm, String name,
			boolean agree) {
		MockHttpServletRequestBuilder request = post("/signup").with(csrf()).param("email", email)
				.param("password", password).param("passwordConfirm", confirm).param("name", name)
				// 브라우저는 체크하지 않은 체크박스를 보내지 않는다. Thymeleaf가 넣는 숨은 칸(_agreeTerms)만 온다
				.param("_agreeTerms", "on");
		return agree ? request.param("agreeTerms", "true") : request;
	}

	private Integer countLandlords() {
		return jdbcTemplate.queryForObject("select count(*) from landlord", Integer.class);
	}

}
