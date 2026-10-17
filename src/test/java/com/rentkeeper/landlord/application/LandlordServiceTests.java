package com.rentkeeper.landlord.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.rentkeeper.TestcontainersConfiguration;
import com.rentkeeper.landlord.domain.EmailAddress;
import com.rentkeeper.landlord.domain.Landlord;
import com.rentkeeper.landlord.domain.LandlordRepository;
import com.rentkeeper.landlord.domain.LandlordStatus;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LandlordServiceTests {

	@Autowired
	LandlordService landlordService;

	@Autowired
	LandlordRepository landlordRepository;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@BeforeEach
	void cleanUp() {
		jdbcTemplate.update("delete from landlord");
	}

	@Test
	void registersLocalLandlordAndFindsCredentialsByNormalizedEmail() {
		long id = landlordService.registerLocal(EmailAddress.of("Kim@Example.com"), "{bcrypt}hash", "김임대");

		LandlordCredentials credentials = landlordService.findLocalCredentials(EmailAddress.of("KIM@example.COM"))
				.orElseThrow();
		assertThat(credentials.id()).isEqualTo(id);
		assertThat(credentials.email()).isEqualTo("kim@example.com");
		assertThat(credentials.passwordHash()).isEqualTo("{bcrypt}hash");
		assertThat(credentials.status()).isEqualTo(LandlordStatus.ACTIVE);
	}

	@Test
	void rejectsDuplicateEmailIgnoringCase() {
		landlordService.registerLocal(EmailAddress.of("kim@example.com"), "{bcrypt}hash", "김임대");

		assertThatThrownBy(
				() -> landlordService.registerLocal(EmailAddress.of("KIM@Example.com"), "{bcrypt}other", "다른사람"))
				.isInstanceOf(DuplicateEmailException.class);
		assertThat(countLandlords()).isEqualTo(1);
	}

	@Test
	void databaseRejectsDuplicateLocalEmailEvenWithoutServiceCheck() {
		insert("kim@example.com", "LOCAL", "{bcrypt}hash");

		assertThatThrownBy(() -> insert("kim@example.com", "LOCAL", "{bcrypt}hash"))
				.isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("uk_landlord_local_email");
	}

	@Test
	void recognizesUniqueViolationRaisedThroughJpa() {
		// 존재 확인과 INSERT 사이에 다른 가입이 끼어든 상황을 재현: 서비스의 존재 확인을 건너뛰고 JPA로 바로 저장한다.
		// 이때 나는 예외를 서비스가 DuplicateEmailException으로 바꿀 수 있어야 한다
		landlordService.registerLocal(EmailAddress.of("kim@example.com"), "{bcrypt}hash", "김임대");

		Throwable jpaError = catchThrowable(() -> landlordRepository
				.saveAndFlush(Landlord.registerLocal(EmailAddress.of("kim@example.com"), "{bcrypt}other", "다른사람")));

		assertThat(jpaError).isInstanceOf(DataIntegrityViolationException.class);
		assertThat(LandlordService.isLocalEmailUniqueViolation(jpaError)).isTrue();
	}

	@Test
	void doesNotTreatOtherIntegrityErrorsAsDuplicateEmail() {
		assertThat(LandlordService.isLocalEmailUniqueViolation(new DataIntegrityViolationException("other"))).isFalse();
	}

	@Test
	void kakaoAccountMayShareEmailWithLocalAccount() {
		// 자동 병합 금지: 같은 이메일이어도 공급자가 다르면 별도 계정
		insert("kim@example.com", "LOCAL", "{bcrypt}hash");
		// 카카오 계정은 회원번호가 반드시 있다 (V9 ck_landlord_kakao_credentials)
		jdbcTemplate.update("""
				insert into landlord (email, auth_provider, kakao_id, name, created_at, updated_at)
				values ('kim@example.com', 'KAKAO', '1234567890', '테스트', now(), now())
				""");

		assertThat(countLandlords()).isEqualTo(2);
	}

	@Test
	void databaseRejectsNonNormalizedEmail() {
		assertThatThrownBy(() -> insert("Kim@Example.com", "LOCAL", "{bcrypt}hash"))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("ck_landlord_email_normalized");
		assertThatThrownBy(() -> insert(" kim@example.com", "LOCAL", "{bcrypt}hash"))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("ck_landlord_email_normalized");
	}

	@Test
	void databaseRequiresCredentialsForLocalAccount() {
		assertThatThrownBy(() -> insert("kim@example.com", "LOCAL", null))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("ck_landlord_local_credentials");
		assertThatThrownBy(() -> insert(null, "LOCAL", "{bcrypt}hash"))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("ck_landlord_local_credentials");
	}

	@Test
	void databaseRejectsUnknownAuthProvider() {
		assertThatThrownBy(() -> insert("kim@example.com", "NAVER", "{bcrypt}hash"))
				.isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_landlord_auth_provider");
	}

	private void insert(String email, String authProvider, String passwordHash) {
		jdbcTemplate.update("""
				insert into landlord (email, password_hash, auth_provider, name, created_at, updated_at)
				values (?, ?, ?, '테스트', now(), now())
				""", email, passwordHash, authProvider);
	}

	private Integer countLandlords() {
		return jdbcTemplate.queryForObject("select count(*) from landlord", Integer.class);
	}

}
