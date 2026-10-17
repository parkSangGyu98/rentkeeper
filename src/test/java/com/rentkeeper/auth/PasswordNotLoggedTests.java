package com.rentkeeper.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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

import com.rentkeeper.TestcontainersConfiguration;

/**
 * 가입·로그인 과정에서 원문 비밀번호가 로그에 남지 않는지 확인한다 (PRD 0장 7번). 보안·웹 로그를 DEBUG로 켜서, 운영보다 더
 * 많이 남기는 상황에서도 비밀번호가 없어야 한다.
 */
@SpringBootTest(properties = {"logging.level.org.springframework.security=DEBUG",
		"logging.level.org.springframework.web=DEBUG", "logging.level.com.rentkeeper=DEBUG"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class PasswordNotLoggedTests {

	private static final String SECRET = "Unique-Secret-9137-xyz";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Test
	void rawPasswordNeverAppearsInLogs(CapturedOutput output) throws Exception {
		jdbcTemplate.update("delete from spring_session");
		jdbcTemplate.update("delete from landlord");

		// 가입 실패(확인 불일치) → 가입 성공 → 로그인 실패 → 로그인 성공
		mockMvc.perform(post("/signup").with(csrf()).param("email", "log@example.com").param("password", SECRET)
				.param("passwordConfirm", SECRET + "-different").param("name", "로그").param("agreeTerms", "true"));
		mockMvc.perform(post("/signup").with(csrf()).param("email", "log@example.com").param("password", SECRET)
				.param("passwordConfirm", SECRET).param("name", "로그").param("agreeTerms", "true"));
		mockMvc.perform(formLogin("/login").userParameter("email").user("log@example.com").password(SECRET + "-wrong"));
		mockMvc.perform(formLogin("/login").userParameter("email").user("log@example.com").password(SECRET));

		assertThat(output.getAll()).as("DEBUG 로그가 실제로 켜졌는지").contains("o.s.security");
		assertThat(output.getAll()).doesNotContain(SECRET);
	}

}
