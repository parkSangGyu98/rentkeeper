package com.rentkeeper.landlord.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.rentkeeper.TestcontainersConfiguration;
import com.rentkeeper.landlord.domain.EmailAddress;
import com.rentkeeper.support.TestData;

/**
 * 탈퇴 유예 중인 계정과 새 계정이 같은 이메일·카카오 회원번호로 함께 있을 수 있다 (M1-c 결정 1: B안, V9). 조회는 사용
 * 중(ACTIVE)인 계정만 대상으로 해야 한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class WithdrawnAccountCoexistenceTests {

	private static final String EMAIL = "kim@example.com";

	private static final String PASSWORD = "Haengbok-Villa-2026";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	LandlordService landlordService;

	@BeforeEach
	void setUp() {
		TestData.reset(jdbcTemplate);
	}

	@Test
	void sameEmailCanSignUpAgainWhileWithdrawnAccountWaitsForPurge() throws Exception {
		insertWithdrawnLocal(EMAIL);

		assertThat(landlordService.isLocalEmailTaken(EmailAddress.of(EMAIL))).isFalse();
		assertThat(landlordService.findLocalCredentials(EmailAddress.of(EMAIL))).isEmpty();

		mockMvc.perform(post("/signup").with(csrf()).param("email", EMAIL).param("password", PASSWORD)
				.param("passwordConfirm", PASSWORD).param("name", "새계정").param("agreeTerms", "true"))
				.andExpect(redirectedUrl("/app"));

		assertThat(landlordService.findLocalCredentials(EmailAddress.of(EMAIL))).get()
				.extracting(LandlordCredentials::name).isEqualTo("새계정");
		jdbcTemplate.update("delete from rate_limit_bucket");
		mockMvc.perform(post("/login").with(csrf()).param("email", EMAIL).param("password", PASSWORD))
				.andExpect(redirectedUrl("/app"));
	}

	@Test
	void twoActiveAccountsWithSameEmailAreStillRejected() {
		insertLocal(EMAIL, "ACTIVE", null);

		assertThatThrownBy(() -> insertLocal(EMAIL, "ACTIVE", null)).isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("uk_landlord_local_email");
	}

	@Test
	void kakaoIdIsUniqueOnlyAmongActiveKakaoAccounts() {
		insertKakao("1234567890", "WITHDRAWN");
		insertKakao("1234567890", "ACTIVE");

		assertThatThrownBy(() -> insertKakao("1234567890", "ACTIVE"))
				.isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("uk_landlord_kakao_id");
	}

	@Test
	void databaseEnforcesKakaoAndWithdrawalRules() {
		assertRejected("insert into landlord (auth_provider, kakao_id, password_hash, name, created_at, updated_at) "
				+ "values ('KAKAO', null, null, 'x', now(), now())", "ck_landlord_kakao_credentials");
		assertRejected("insert into landlord (auth_provider, kakao_id, password_hash, name, created_at, updated_at) "
				+ "values ('KAKAO', '123', '{bcrypt}x', 'x', now(), now())", "ck_landlord_kakao_credentials");
		assertRejected(
				"insert into landlord (auth_provider, email, password_hash, kakao_id, name, created_at, updated_at) "
						+ "values ('LOCAL', 'a@example.com', '{bcrypt}x', '123', 'x', now(), now())",
				"ck_landlord_local_no_kakao");
		assertRejected("insert into landlord (auth_provider, kakao_id, name, created_at, updated_at) "
				+ "values ('KAKAO', 'abc', 'x', now(), now())", "ck_landlord_kakao_id_digits");
		assertRejected(
				"insert into landlord (auth_provider, email, password_hash, name, status, created_at, updated_at) "
						+ "values ('LOCAL', 'b@example.com', '{bcrypt}x', 'x', 'WITHDRAWN', now(), now())",
				"ck_landlord_withdrawn");
		assertRejected(
				"insert into landlord (auth_provider, email, password_hash, name, withdrawn_at, created_at, updated_at) "
						+ "values ('LOCAL', 'c@example.com', '{bcrypt}x', 'x', now(), now(), now())",
				"ck_landlord_withdrawn");
	}

	private void assertRejected(String sql, String constraint) {
		assertThatThrownBy(() -> jdbcTemplate.update(sql)).isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining(constraint);
	}

	private void insertWithdrawnLocal(String email) {
		insertLocal(email, "WITHDRAWN", "now()");
	}

	private void insertLocal(String email, String status, String withdrawnAt) {
		jdbcTemplate.update("insert into landlord (auth_provider, email, password_hash, name, status, withdrawn_at, "
				+ "created_at, updated_at) values ('LOCAL', ?, '{bcrypt}x', '예전계정', ?, "
				+ (withdrawnAt == null ? "null" : withdrawnAt) + ", now(), now())", email, status);
	}

	private void insertKakao(String kakaoId, String status) {
		jdbcTemplate.update("insert into landlord (auth_provider, kakao_id, name, status, withdrawn_at, created_at, "
				+ "updated_at) values ('KAKAO', ?, '카카오', ?, " + ("WITHDRAWN".equals(status) ? "now()" : "null")
				+ ", now(), now())", kakaoId, status);
	}

}
