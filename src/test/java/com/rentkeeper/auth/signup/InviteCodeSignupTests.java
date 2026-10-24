package com.rentkeeper.auth.signup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.rentkeeper.TestcontainersConfiguration;
import com.rentkeeper.support.TestData;

/**
 * 초대 코드가 있어야 가입한다 (M6 커밋 3, 결정 3). 코드가 없거나 틀리면 계정을 만들지 않고, 이메일 중복 같은 다른 확인도 하지
 * 않는다. 시도는 IP당 1시간 10회. 코드는 로그에 남지 않는다.
 */
@SpringBootTest(properties = "app.signup.invite-code=" + InviteCodeSignupTests.CODE)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class InviteCodeSignupTests {

	static final String CODE = "test-invite-code-2026";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	@BeforeEach
	void setUp() {
		TestData.reset(jdbc);
	}

	@Test
	void signupFormAsksForTheInviteCode() throws Exception {
		String page = mockMvc.perform(get("/signup")).andReturn().getResponse().getContentAsString();

		assertThat(page).contains("초대 코드").contains("name=\"inviteCode\"").contains("초대받은 분만 가입할 수 있어요");
	}

	@Test
	void missingOrWrongCodeCreatesNothing() throws Exception {
		for (String code : new String[]{"", "wrong", CODE + "x", CODE.substring(1)}) {
			MvcResult result = signup("kim@example.com", code);
			assertThat(result.getResponse().getStatus()).as(code).isEqualTo(200);
			assertThat(result.getResponse().getContentAsString()).contains("초대 코드가 맞지 않아요");
		}
		assertThat(landlords()).isZero();
	}

	@Test
	void wrongCodeDoesNotRevealExistingEmails() throws Exception {
		signup("kim@example.com", CODE);

		String page = signup("kim@example.com", "wrong").getResponse().getContentAsString();

		assertThat(page).contains("초대 코드가 맞지 않아요").doesNotContain("이미 가입된 이메일이에요");
	}

	@Test
	void rightCodeSignsUp() throws Exception {
		MvcResult result = signup("kim@example.com", "  " + CODE + "  ");

		assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/app");
		assertThat(landlords()).isEqualTo(1);
	}

	@Test
	void attemptsAreLimitedPerIp(CapturedOutput output) throws Exception {
		for (int i = 0; i < 10; i++) {
			signup("k" + i + "@example.com", "wrong-" + i);
		}

		// 가입 한도(IP당 1시간 10회, ADR-012)와 초대 코드 한도가 함께 걸린다: 11번째는 맞는 코드여도 막힌다
		assertThat(signup("ok@example.com", CODE).getResponse().getStatus()).isEqualTo(429);
		assertThat(landlords()).isZero();
		assertThat(output).doesNotContain(CODE).doesNotContain("wrong-3");
	}

	private MvcResult signup(String email, String code) throws Exception {
		return mockMvc.perform(post("/signup").with(csrf()).param("email", email)
				.param("password", "Haengbok-Villa-2026").param("passwordConfirm", "Haengbok-Villa-2026")
				.param("name", "김임대").param("agreeTerms", "true").param("inviteCode", code)).andReturn();
	}

	private int landlords() {
		return jdbc.queryForObject("select count(*) from landlord", Integer.class);
	}

}
