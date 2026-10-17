package com.rentkeeper.consent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

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
import com.rentkeeper.site.LegalDocument;
import com.rentkeeper.support.MutableClock;
import com.rentkeeper.support.MutableClockConfiguration;
import com.rentkeeper.support.TestData;

/**
 * 가입할 때 약관·개인정보처리방침 동의가 계정 생성과 함께 기록된다 (AUTH-6, ADR-016).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, MutableClockConfiguration.class})
class ConsentRecordingTests {

	private static final String PASSWORD = "Haengbok-Villa-2026";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	ConsentService consentService;

	@Autowired
	MutableClock clock;

	@BeforeEach
	void setUp() {
		clock.set(MutableClockConfiguration.START);
		TestData.reset(jdbcTemplate);
	}

	@Test
	void signupRecordsTermsAndPrivacyWithCurrentVersionAndClockTime() throws Exception {
		signup("kim@example.com").andExpect(redirectedUrl("/app"));
		long landlordId = jdbcTemplate.queryForObject("select id from landlord", Long.class);

		List<ConsentLog> logs = consentService.findByLandlord(landlordId);
		assertThat(logs).extracting(ConsentLog::getDocumentType).containsExactlyInAnyOrder(LegalDocument.TERMS,
				LegalDocument.PRIVACY);
		assertThat(logs).extracting(ConsentLog::getDocumentVersion).containsOnly("v0.1");
		assertThat(logs).extracting(ConsentLog::getAgreedAt).containsOnly(MutableClockConfiguration.START);
	}

	@Test
	void failedSignupLeavesNoConsentRecord() throws Exception {
		signup("kim@example.com");
		signup("KIM@example.com").andExpect(status().isOk()); // 중복 이메일 → 가입 실패

		assertThat(jdbcTemplate.queryForObject("select count(*) from consent_log", Integer.class)).isEqualTo(2);
	}

	@Test
	void consentRowsAreDeletedWithLandlord() throws Exception {
		signup("kim@example.com");
		jdbcTemplate.update("delete from landlord");

		assertThat(jdbcTemplate.queryForObject("select count(*) from consent_log", Integer.class)).isZero();
	}

	@Test
	void databaseRejectsUnknownDocumentType() throws Exception {
		signup("kim@example.com");
		long landlordId = jdbcTemplate.queryForObject("select id from landlord", Long.class);

		assertThatThrownBy(() -> jdbcTemplate.update(
				"insert into consent_log (landlord_id, document_type, document_version, agreed_at) values (?, 'MARKETING', 'v1', now())",
				landlordId)).isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("ck_consent_log_document_type");
	}

	private org.springframework.test.web.servlet.ResultActions signup(String email) throws Exception {
		return mockMvc.perform(post("/signup").with(csrf()).param("email", email).param("password", PASSWORD)
				.param("passwordConfirm", PASSWORD).param("name", "김임대").param("agreeTerms", "true"));
	}

}
