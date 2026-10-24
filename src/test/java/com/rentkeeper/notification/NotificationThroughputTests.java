package com.rentkeeper.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.rentkeeper.TestcontainersConfiguration;
import com.rentkeeper.common.crypto.FieldEncryptor;
import com.rentkeeper.notification.application.NotificationPlanner;
import com.rentkeeper.notification.application.NotificationWorker;
import com.rentkeeper.support.MutableClock;
import com.rentkeeper.support.MutableClockConfiguration;
import com.rentkeeper.support.NotificationTestConfiguration;
import com.rentkeeper.support.NotificationTestConfiguration.MutableNotificationChannels;
import com.rentkeeper.support.NotificationTestConfiguration.RecordingNotificationSender;
import com.rentkeeper.support.NotificationTestConfiguration.TestComposer;
import com.rentkeeper.support.TestData;

/**
 * 알림 계획·발송 처리량 (M5 커밋 7). 오래 걸려 일반 빌드에서는 빠지고 {@code gradlew perfTest}로만 돈다. 결과는
 * ADR-028에 적는다.
 * <p>
 * 가장 무거운 날을 만든다: 임대인 2,000명 × 계약 10건이 모두 내일이 납부일(전날 안내 2만 건)이고, 임대인마다 어제가 납부일이던
 * 납부일 지남이 하나씩 있다(요약 2,000통). 발송은 세입자 채널을 켜고 실제 본문 만들기(알림 링크 만들기 포함)로 잰다.
 */
@Tag("perf")
@SpringBootTest
@Import({TestcontainersConfiguration.class, MutableClockConfiguration.class, NotificationTestConfiguration.class})
class NotificationThroughputTests {

	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	private static final int LANDLORDS = 2_000;

	private static final int LEASES_PER_LANDLORD = 10;

	/** 발송을 잴 묶음 수 (묶음당 50건) */
	private static final int WORKER_BATCHES = 40;

	@Autowired
	NotificationPlanner planner;

	@Autowired
	NotificationWorker worker;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	MutableClock clock;

	@Autowired
	FieldEncryptor encryptor;

	@Autowired
	RecordingNotificationSender sender;

	@Autowired
	MutableNotificationChannels channels;

	@Autowired
	TestComposer composer;

	@Test
	void planTwentyThousandNoticesAndMeasureSending() {
		TestData.reset(jdbc);
		NotificationTestConfiguration.reset(sender, channels, composer);
		channels.tenantPhone(true);
		seed();
		clock.set(LocalDateTime.parse("2026-11-07T08:00").atZone(SEOUL).toInstant());

		long startedAt = System.nanoTime();
		NotificationPlanner.Result tenants = planner.planTenantNotices();
		Duration tenantPlan = Duration.ofNanos(System.nanoTime() - startedAt);
		startedAt = System.nanoTime();
		NotificationPlanner.Result digests = planner.planLandlordDigests();
		Duration digestPlan = Duration.ofNanos(System.nanoTime() - startedAt);
		startedAt = System.nanoTime();
		NotificationPlanner.Result again = planner.planTenantNotices();
		Duration quietPlan = Duration.ofNanos(System.nanoTime() - startedAt);
		System.out.printf("THROUGHPUT notification plan: tenants %s for %s, digests %s for %s, rerun %s for %s%n",
				tenantPlan, tenants, digestPlan, digests, quietPlan, again);
		assertThat(tenants.created()).isEqualTo(LANDLORDS * LEASES_PER_LANDLORD);
		assertThat(digests.created()).isEqualTo(LANDLORDS);
		assertThat(again.created()).isZero();
		assertThat(tenantPlan.plus(digestPlan)).isLessThan(Duration.ofMinutes(5));

		clock.set(Instant.parse("2026-11-07T00:00:00Z")); // 09:00 서울
		jdbc.execute("analyze notification");
		System.out.println("THROUGHPUT claim plan:%n".formatted() + String.join("%n".formatted(),
				jdbc.queryForList("explain analyze select id from notification where (status = 'PENDING' and "
						+ "next_attempt_at <= '2026-11-07T00:00:00Z') or (status = 'SENDING' and claimed_until <= '2026-11-07T00:00:00Z') "
						+ "order by next_attempt_at, id limit 50", String.class)));
		startedAt = System.nanoTime();
		int sent = 0;
		for (int i = 0; i < WORKER_BATCHES; i++) {
			sent += worker.runOnce().sent();
		}
		Duration sending = Duration.ofNanos(System.nanoTime() - startedAt);
		System.out.printf("THROUGHPUT notification send: %d sent in %s (%.1f/s, %d batches of 50)%n", sent, sending,
				sent / (sending.toMillis() / 1000.0), WORKER_BATCHES);
		assertThat(sent).isEqualTo(WORKER_BATCHES * 50);
		TestData.reset(jdbc);
	}

	/** 계약 2만 건(받는 날 8일), 11월분 받을 차례(납부일 11/8), 임대인마다 10월분 하나는 11/6이 납부일인 납부일 지남 */
	private void seed() {
		String accountNo = encryptor.encrypt("1234567890", "lease.account_no");
		String accountHolder = encryptor.encrypt("김임대", "lease.account_holder");
		String tenantName = encryptor.encrypt("세입자", "tenant.name");
		String tenantPhone = encryptor.encrypt("01012345678", "tenant.phone");

		jdbc.update("insert into landlord (auth_provider, email, email_verified_at, password_hash, name, created_at, "
				+ "updated_at) select 'LOCAL', 'perf' || g || '@example.com', now(), '{bcrypt}x', '임대인', now(), now() "
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
						+ "now(), now() from unit u join tenant t "
						+ "on t.landlord_id = u.landlord_id and t.phone_hash = md5(u.id::text) || md5(u.id::text)",
				accountNo, accountHolder);
		jdbc.update("insert into charge (landlord_id, lease_id, billing_month, due_date, total_amount, status, source, "
				+ "issued_at, created_at, updated_at) select landlord_id, id, '2026-11-01', '2026-11-08', 500000, "
				+ "'ISSUED', 'AUTO', now(), now(), now() from lease");
		jdbc.update("insert into charge (landlord_id, lease_id, billing_month, due_date, total_amount, status, source, "
				+ "issued_at, created_at, updated_at) select distinct on (landlord_id) landlord_id, id, '2026-10-01', "
				+ "'2026-11-06', 500000, 'OVERDUE', 'AUTO', now(), now(), now() from lease order by landlord_id, id");
		assertThat(jdbc.queryForObject("select count(*) from lease", Integer.class))
				.isEqualTo(LANDLORDS * LEASES_PER_LANDLORD);
	}

}
