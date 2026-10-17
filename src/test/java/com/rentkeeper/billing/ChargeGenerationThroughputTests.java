package com.rentkeeper.billing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.rentkeeper.TestcontainersConfiguration;
import com.rentkeeper.billing.application.ChargeGenerator;
import com.rentkeeper.common.crypto.FieldEncryptor;
import com.rentkeeper.config.ClockConfig;
import com.rentkeeper.support.MutableClock;
import com.rentkeeper.support.MutableClockConfiguration;
import com.rentkeeper.support.TestData;

/**
 * 청구 생성 배치 처리량 (PRD 8.3: 계약 2만 건 5분 안). 오래 걸려 일반 빌드에서는 빠지고
 * {@code gradlew perfTest}로만 돈다. 결과는 ADR-024에 적는다.
 * <p>
 * 가장 무거운 날을 만든다: 임대인 2,000명 × 계약 10건이 모두 같은 날(11/1) 11월분 청구(항목 2개)를 만든다.
 */
@Tag("perf")
@SpringBootTest
@Import({TestcontainersConfiguration.class, MutableClockConfiguration.class})
class ChargeGenerationThroughputTests {

	private static final int LANDLORDS = 2_000;

	private static final int LEASES_PER_LANDLORD = 10;

	@Autowired
	ChargeGenerator generator;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	MutableClock clock;

	@Autowired
	FieldEncryptor encryptor;

	@Test
	void twentyThousandLeasesWithinFiveMinutes() {
		TestData.reset(jdbc);
		seed();
		clock.set(LocalDateTime.parse("2026-11-01T03:00").atZone(ClockConfig.SEOUL).toInstant());

		long startedAt = System.nanoTime();
		ChargeGenerator.Result first = generator.run();
		Duration busyDay = Duration.ofNanos(System.nanoTime() - startedAt);

		startedAt = System.nanoTime();
		ChargeGenerator.Result second = generator.run();
		Duration quietDay = Duration.ofNanos(System.nanoTime() - startedAt);

		System.out.printf("THROUGHPUT busy: %s for %s (%.1f leases/s), quiet rerun: %s for %s%n", busyDay, first,
				first.leases() / (busyDay.toMillis() / 1000.0), quietDay, second);
		assertThat(first.created()).isEqualTo(LANDLORDS * LEASES_PER_LANDLORD);
		assertThat(first.failed()).isZero();
		assertThat(second.created()).isZero();
		assertThat(busyDay).isLessThan(Duration.ofMinutes(5));
		TestData.reset(jdbc);
	}

	/** 계약 2만 건: 1/1 입주, 2028/1/1 퇴실, 납부일 8일(만들 날 11/1), 10/31 등록이라 11월분부터 자동 */
	private void seed() {
		Instant registered = LocalDateTime.parse("2026-10-31T12:00").atZone(ClockConfig.SEOUL).toInstant();
		String at = "'" + registered + "'::timestamptz";
		// 배치가 계약을 읽으며 계좌를 복호화하므로 실제 암호문을 쓴다 (용도별 AAD라 다른 행에 복사해도 풀린다)
		String accountNo = encryptor.encrypt("1234567890", "lease.account_no");
		String accountHolder = encryptor.encrypt("김임대", "lease.account_holder");
		String tenantName = encryptor.encrypt("세입자", "tenant.name");
		String tenantPhone = encryptor.encrypt("01012345678", "tenant.phone");

		jdbc.update("insert into landlord (auth_provider, email, password_hash, name, created_at, updated_at) "
				+ "select 'LOCAL', 'perf' || g || '@example.com', '{bcrypt}x', '임대인', now(), now() "
				+ "from generate_series(1, ?) g", LANDLORDS);
		jdbc.update("insert into property (landlord_id, name, created_at, updated_at) "
				+ "select id, '행복빌라', now(), now() from landlord");
		jdbc.update("insert into unit (landlord_id, property_id, name, type, created_at, updated_at) "
				+ "select p.landlord_id, p.id, g || '01호', 'ONE_ROOM', now(), now() "
				+ "from property p cross join generate_series(1, ?) g", LEASES_PER_LANDLORD);
		jdbc.update(
				"insert into tenant (landlord_id, name_enc, phone_enc, phone_hash, created_at, updated_at) "
						+ "select landlord_id, ?, ?, md5(id::text) || md5(id::text), now(), now() from unit",
				tenantName, tenantPhone);
		jdbc.update(
				"insert into lease (landlord_id, unit_id, tenant_id, deposit, due_day, start_date, end_date, status, "
						+ "bank_name, account_no_enc, account_holder_enc, created_at, updated_at) "
						+ "select u.landlord_id, u.id, t.id, 0, 8, '2026-01-01', '2028-01-01', 'ACTIVE', '국민은행', ?, ?, "
						+ at + ", " + at + " from unit u join tenant t "
						+ "on t.landlord_id = u.landlord_id and t.phone_hash = md5(u.id::text) || md5(u.id::text)",
				accountNo, accountHolder);
		jdbc.update("insert into lease_rent_history (landlord_id, lease_id, monthly_rent, maintenance_fee, "
				+ "effective_from, created_at, updated_at) select landlord_id, id, 500000, 50000, '2026-01-01', now(), "
				+ "now() from lease");
		assertThat(jdbc.queryForObject("select count(*) from lease", Integer.class))
				.isEqualTo(LANDLORDS * LEASES_PER_LANDLORD);
	}

}
