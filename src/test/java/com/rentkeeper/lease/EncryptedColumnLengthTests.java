package com.rentkeeper.lease;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.rentkeeper.TestcontainersConfiguration;
import com.rentkeeper.common.crypto.EncryptionProperties;
import com.rentkeeper.common.crypto.FieldEncryptor;
import com.rentkeeper.support.TestData;

/**
 * 암호문 컬럼이 최대 입력의 최악의 경우에도 들어가는지 (V14). 입력 길이는 글자(코드 포인트) 기준이고, UTF-8에서 한 글자는 최대
 * 4바이트(이모지)다. 키 버전이 네 자리로 늘어나도("v9999:") 들어가야 한다.
 * <p>
 * 컬럼 길이를 줄이는 변경이 생기면 이 테스트가 실패한다: 실제 컬럼에 넣어 보고, DB에 선언된 길이와 계산한 최댓값도 비교한다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class EncryptedColumnLengthTests {

	/** 앱에서 받는 최대 글자 수. 도메인 값 타입·폼 검증과 같아야 한다 */
	static final int NAME_MAX = 50;

	static final int MEMO_MAX = 500;

	static final int PHONE_DIGITS_MAX = 11;

	static final int ACCOUNT_DIGITS_MAX = 30;

	private static final String EMOJI = "😀";

	private static final int[] KEY_VERSIONS = {1, 99, 999, 9999};

	@Autowired
	JdbcTemplate jdbc;

	long landlordId;

	long unitId;

	@BeforeEach
	void setUp() {
		TestData.reset(jdbc);
		landlordId = jdbc.queryForObject("insert into landlord (auth_provider, email, password_hash, name, created_at, "
				+ "updated_at) values ('LOCAL', 'len@example.com', '{bcrypt}x', '임대인', now(), now()) returning id",
				Long.class);
		long propertyId = jdbc.queryForObject("insert into property (landlord_id, name, created_at, updated_at) "
				+ "values (?, '빌라', now(), now()) returning id", Long.class, landlordId);
		unitId = jdbc.queryForObject(
				"insert into unit (landlord_id, property_id, name, type, created_at, updated_at) "
						+ "values (?, ?, '101호', 'ONE_ROOM', now(), now()) returning id",
				Long.class, landlordId, propertyId);
	}

	@Test
	void worstCaseCiphertextsFitEveryEncryptedColumnForAnyKeyVersion() {
		for (int i = 0; i < KEY_VERSIONS.length; i++) {
			int version = KEY_VERSIONS[i];
			FieldEncryptor encryptor = encryptor(version);
			String phoneHash = String.format("%064x", version);

			// 세입자: 이름 이모지 50자, 연락처 11자리, 메모 이모지 500자
			long tenantId = jdbc.queryForObject(
					"insert into tenant (landlord_id, name_enc, phone_enc, phone_hash, memo_enc, "
							+ "created_at, updated_at) values (?, ?, ?, ?, ?, now(), now()) returning id",
					Long.class, landlordId, encryptor.encrypt(EMOJI.repeat(NAME_MAX), "tenant.name"),
					encryptor.encrypt("9".repeat(PHONE_DIGITS_MAX), "tenant.phone"), phoneHash,
					encryptor.encrypt(EMOJI.repeat(MEMO_MAX), "tenant.memo"));

			// 계약: 계좌번호 30자리, 예금주 이모지 50자 (기간이 겹치지 않게 버전마다 다른 해)
			int year = 2001 + i;
			jdbc.update(
					"insert into lease (landlord_id, unit_id, tenant_id, deposit, due_day, start_date, end_date, status, "
							+ "bank_name, account_no_enc, account_holder_enc, created_at, updated_at) values (?, ?, ?, 0, 1, "
							+ "make_date(?, 1, 1), make_date(?, 12, 31), 'ACTIVE', ?, ?, ?, now(), now())",
					landlordId, unitId, tenantId, year, year, EMOJI.repeat(50),
					encryptor.encrypt("9".repeat(ACCOUNT_DIGITS_MAX), "lease.account_no"),
					encryptor.encrypt(EMOJI.repeat(NAME_MAX), "lease.account_holder"));
		}
		assertThat(jdbc.queryForObject("select count(*) from tenant", Integer.class)).isEqualTo(KEY_VERSIONS.length);
		assertThat(jdbc.queryForObject("select count(*) from lease", Integer.class)).isEqualTo(KEY_VERSIONS.length);
	}

	@Test
	void declaredColumnLengthsCoverComputedWorstCase() {
		int maxVersionDigits = 4;
		assertThat(columnLength("tenant", "memo_enc"))
				.isGreaterThanOrEqualTo(worstCase(4 * MEMO_MAX, maxVersionDigits));
		assertThat(columnLength("tenant", "name_enc"))
				.isGreaterThanOrEqualTo(worstCase(4 * NAME_MAX, maxVersionDigits));
		assertThat(columnLength("tenant", "phone_enc"))
				.isGreaterThanOrEqualTo(worstCase(PHONE_DIGITS_MAX, maxVersionDigits));
		assertThat(columnLength("lease", "account_no_enc"))
				.isGreaterThanOrEqualTo(worstCase(ACCOUNT_DIGITS_MAX, maxVersionDigits));
		assertThat(columnLength("lease", "account_holder_enc"))
				.isGreaterThanOrEqualTo(worstCase(4 * NAME_MAX, maxVersionDigits));
		// 계산식이 실제 암호문 길이와 맞는지
		assertThat(encryptor(9999).encrypt(EMOJI.repeat(MEMO_MAX), "tenant.memo"))
				.hasSize(worstCase(4 * MEMO_MAX, maxVersionDigits));
	}

	/** "v" + 버전 + ":" + base64url(IV 12 + 평문 + 태그 16), 패딩 없음 */
	static int worstCase(int plaintextBytes, int versionDigits) {
		int payload = plaintextBytes + 28;
		int base64 = (payload * 4 + 2) / 3;
		return 2 + versionDigits + base64;
	}

	private int columnLength(String table, String column) {
		return jdbc.queryForObject("select character_maximum_length from information_schema.columns "
				+ "where table_name = ? and column_name = ?", Integer.class, table, column);
	}

	private static FieldEncryptor encryptor(int version) {
		String key = Base64.getEncoder().encodeToString("rentkeeper-test-only-enc-key-v1!".getBytes());
		return new FieldEncryptor(new EncryptionProperties(key, String.valueOf(version), "", key));
	}

}
