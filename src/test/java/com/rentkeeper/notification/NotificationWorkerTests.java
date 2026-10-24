package com.rentkeeper.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.rentkeeper.TestcontainersConfiguration;
import com.rentkeeper.landlord.application.LandlordService;
import com.rentkeeper.lease.application.LeaseService;
import com.rentkeeper.lease.application.LeaseViews.LeaseRegistration;
import com.rentkeeper.lease.domain.AccountNumber;
import com.rentkeeper.lease.domain.LeaseTerms;
import com.rentkeeper.lease.domain.PersonName;
import com.rentkeeper.lease.domain.PhoneNumber;
import com.rentkeeper.notification.application.NotificationOutbox;
import com.rentkeeper.notification.application.NotificationRequest;
import com.rentkeeper.notification.application.NotificationWorker;
import com.rentkeeper.notification.application.OutboundNotification;
import com.rentkeeper.notification.domain.NotificationType;
import com.rentkeeper.support.LandlordLogin;
import com.rentkeeper.support.MutableClock;
import com.rentkeeper.support.MutableClockConfiguration;
import com.rentkeeper.support.NotificationTestConfiguration;
import com.rentkeeper.support.NotificationTestConfiguration.MutableNotificationChannels;
import com.rentkeeper.support.NotificationTestConfiguration.RecordingNotificationSender;
import com.rentkeeper.support.NotificationTestConfiguration.TestComposer;
import com.rentkeeper.support.TestData;

/**
 * 알림 Outbox와 발송 워커 (M5 커밋 2, 결정 2·3·6, ADR-028). 시작 시각 2026-10-17 09:00 서울.
 * <ul>
 * <li>멱등: 같은 dedupe_key는 한 번만, 업무 트랜잭션이 롤백되면 알림도 없다</li>
 * <li>세입자 채널이 꺼져 있으면 SKIPPED, 나중에 켜도 과거 알림은 나가지 않는다</li>
 * <li>발송 시간 09:00~20:00, 그날 못 보내면 EXPIRED, 재시도 1·5·15분 뒤 FAILED</li>
 * <li>워커 두 개가 동시에 돌아도 행마다 한 번, 로그에는 ID·종류·결과만</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, MutableClockConfiguration.class, NotificationTestConfiguration.class})
@ExtendWith(OutputCaptureExtension.class)
class NotificationWorkerTests {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	LandlordService landlordService;

	@Autowired
	PasswordEncoder passwordEncoder;

	@Autowired
	LeaseService leaseService;

	@Autowired
	MutableClock clock;

	@Autowired
	NotificationOutbox outbox;

	@Autowired
	NotificationWorker worker;

	@Autowired
	RecordingNotificationSender sender;

	@Autowired
	MutableNotificationChannels channels;

	@Autowired
	TestComposer composer;

	@Autowired
	PlatformTransactionManager transactionManager;

	long landlord;

	@BeforeEach
	void setUp() throws Exception {
		clock.set(MutableClockConfiguration.START);
		TestData.reset(jdbc);
		NotificationTestConfiguration.reset(sender, channels, composer);
		composer.handle(Set.of(NotificationType.values()));
		landlord = LandlordLogin.signUpAndLogin(mockMvc, landlordService, passwordEncoder, "a@example.com")
				.landlordId();
	}

	@Test
	void sameKeyIsQueuedOnceAndRollbackLeavesNothing() {
		assertThat(outbox.enqueue(digest("K1"))).isTrue();
		assertThat(outbox.enqueue(digest("K1"))).as("같은 키").isFalse();

		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			outbox.enqueue(digest("K2"));
			status.setRollbackOnly();
		});

		assertThat(count("1 = 1")).isEqualTo(1);
		assertThat(worker.runOnce().sent()).isEqualTo(1);
	}

	@Test
	void tenantAlertsAreSkippedWhileTheChannelIsOffAndNeverSentLater() {
		long[] keys = tenantLease();
		for (int day = 0; day < 7; day++) {
			outbox.enqueue(tenantRequest(keys, "CHARGE_ISSUED:" + day));
			clock.advance(Duration.ofDays(1));
		}
		assertThat(statuses()).containsOnlyKeys("SKIPPED:CHANNEL_OFF");

		channels.tenantPhone(true);
		assertThat(worker.runOnce().claimed()).as("켠 뒤 워커").isZero();
		for (int day = 0; day < 7; day++) {
			assertThat(outbox.enqueue(tenantRequest(keys, "CHARGE_ISSUED:" + day))).as("같은 날 다시 계획").isFalse();
		}
		assertThat(worker.runOnce().sent()).isZero();
		assertThat(sender.sent()).isEmpty();

		assertThat(outbox.enqueue(tenantRequest(keys, "CHARGE_ISSUED:new"))).isTrue();
		assertThat(worker.runOnce().sent()).as("켠 뒤 새로 만든 것만").isEqualTo(1);
	}

	@Test
	void turningTheChannelOffBeforeSendingSkips() {
		long[] keys = tenantLease();
		channels.tenantPhone(true);
		outbox.enqueue(tenantRequest(keys, "CHARGE_ISSUED:1"));
		channels.tenantPhone(false);

		assertThat(worker.runOnce().skipped()).isEqualTo(1);
		assertThat(statuses()).containsOnlyKeys("SKIPPED:CHANNEL_OFF");
	}

	@Test
	void waitsForNineAndCreatedAfterEightIsTomorrow() {
		clock.set(Instant.parse("2026-10-16T23:59:00Z")); // 10-17 08:59 서울
		outbox.enqueue(digest("early"));
		assertThat(worker.runOnce().claimed()).isZero();
		clock.advance(Duration.ofMinutes(1));
		assertThat(worker.runOnce().sent()).isEqualTo(1);

		clock.set(Instant.parse("2026-10-17T11:00:00Z")); // 20:00 서울
		outbox.enqueue(digest("late"));
		assertThat(jdbc.queryForObject("select scheduled_for from notification where dedupe_key = 'late'",
				LocalDate.class)).isEqualTo(LocalDate.of(2026, 10, 18));
		clock.set(Instant.parse("2026-10-17T23:59:00Z")); // 10-18 08:59
		assertThat(worker.runOnce().claimed()).isZero();
		clock.advance(Duration.ofMinutes(1));
		assertThat(worker.runOnce().sent()).isEqualTo(1);
	}

	@Test
	void notSentByEightIsExpired() {
		clock.set(Instant.parse("2026-10-17T10:59:00Z")); // 19:59 서울
		outbox.enqueue(digest("K"));
		sender.failNext(1);
		assertThat(worker.runOnce().retried()).isEqualTo(1);

		clock.advance(Duration.ofMinutes(1)); // 20:00
		assertThat(worker.runOnce().expired()).isEqualTo(1);
		assertThat(statuses()).containsOnlyKeys("EXPIRED");
	}

	@Test
	void retriesAfterOneFiveFifteenMinutesAndAlertsOnFailure(CapturedOutput output) {
		outbox.enqueue(digest("K"));
		sender.failNext(3);

		assertThat(worker.runOnce().retried()).isEqualTo(1);
		clock.advance(Duration.ofSeconds(59));
		assertThat(worker.runOnce().claimed()).as("1분 전").isZero();
		clock.advance(Duration.ofSeconds(1));
		assertThat(worker.runOnce().retried()).isEqualTo(1);
		clock.advance(Duration.ofMinutes(5));
		assertThat(worker.runOnce().failed()).isEqualTo(1);

		assertThat(count("type = 'OVERDUE_DIGEST' and status = 'FAILED'")).isEqualTo(1);
		assertThat(count("dedupe_key = 'OPS_ALERT:NOTIFICATION_ALERT:2026-10-17' and status = 'PENDING'"))
				.as("운영 알림 메일 행").isEqualTo(1);
		assertThat(
				jdbc.queryForObject("select attempts from notification where type = 'OVERDUE_DIGEST'", Integer.class))
				.isEqualTo(3);
		assertThat(
				jdbc.queryForObject("select last_error from notification where type = 'OVERDUE_DIGEST'", String.class))
				.isEqualTo("java.lang.IllegalStateException");
		assertThat(output).contains(NotificationWorker.ALERT_CODE);
		assertThat(output).as("예외 메시지(번호·본문)는 남기지 않는다").doesNotContain("01099990000").doesNotContain("비밀본문");
	}

	@Test
	void anExpiredClaimIsTakenOverOnce() {
		outbox.enqueue(digest("K"));
		// 앞 워커가 가져간 뒤 죽은 상황
		jdbc.update("update notification set status = 'SENDING', attempts = 1, claimed_until = ?",
				java.sql.Timestamp.from(clock.instant().plus(Duration.ofMinutes(5))));
		assertThat(worker.runOnce().claimed()).isZero();

		clock.advance(Duration.ofMinutes(5));

		assertThat(worker.runOnce().sent()).isEqualTo(1);
		assertThat(sender.sent()).hasSize(1);
	}

	@Test
	void twoWorkersSendEachRowOnce() throws Exception {
		for (int i = 0; i < 80; i++) {
			outbox.enqueue(digest("K" + i));
		}
		sender.delay(5);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			List<Future<?>> runs = new ArrayList<>();
			for (int t = 0; t < 2; t++) {
				runs.add(pool.submit(() -> {
					start.await();
					worker.runOnce();
					return worker.runOnce();
				}));
			}
			start.countDown();
			for (Future<?> run : runs) {
				run.get();
			}
		} finally {
			pool.shutdown();
		}

		List<Long> ids = sender.sent().stream().map(OutboundNotification::notificationId).toList();
		assertThat(ids).hasSize(80).doesNotHaveDuplicates();
		assertThat(statuses()).containsExactly(Map.entry("SENT", 80));
	}

	@Test
	void logsCarryOnlyIdsTypesAndResults(CapturedOutput output) {
		long[] keys = tenantLease();
		channels.tenantPhone(true);
		outbox.enqueue(tenantRequest(keys, "CHARGE_ISSUED:1"));

		worker.runOnce();

		assertThat(sender.sent()).singleElement().satisfies(m -> {
			assertThat(m.to()).isEqualTo(TestComposer.TO);
			assertThat(m.toString()).doesNotContain(TestComposer.TO).doesNotContain("secret");
		});
		assertThat(output).contains("type=CHARGE_ISSUED channel=TENANT_PHONE recipient=TENANT:" + keys[0])
				.contains("result=SENT");
		assertThat(output).doesNotContain(TestComposer.TO).doesNotContain("010-1111-2222").doesNotContain("secret")
				.doesNotContain("시험 본문");
		assertThat(jdbc.queryForObject("select count(*) from notification where dedupe_key like '%0101111%'"
				+ " or dedupe_key like '%secret%'", Integer.class)).isZero();
	}

	@Test
	void opsAlertsGoOutAtNight() {
		clock.set(Instant.parse("2026-10-17T14:00:00Z")); // 23:00 서울
		outbox.enqueue(NotificationRequest.ops("CHARGE_BATCH_ALERT", "OPS_ALERT:CHARGE_BATCH_ALERT:2026-10-17"));

		assertThat(worker.runOnce().sent()).isEqualTo(1);
	}

	@Test
	void finishedRowsAreNeverTouched() {
		outbox.enqueue(digest("K"));
		worker.runOnce();
		clock.advance(Duration.ofDays(1));

		assertThat(worker.runOnce().claimed()).isZero();
		assertThat(sender.sent()).hasSize(1);
	}

	private NotificationRequest digest(String key) {
		return NotificationRequest.landlord(NotificationType.OVERDUE_DIGEST, landlord, key);
	}

	/** [tenantId, leaseId, chargeId] */
	private long[] tenantLease() {
		long property = jdbc.queryForObject("insert into property (landlord_id, name, created_at, updated_at) "
				+ "values (?, '행복빌라', now(), now()) returning id", Long.class, landlord);
		long unit = jdbc.queryForObject(
				"insert into unit (landlord_id, property_id, name, type, created_at, "
						+ "updated_at) values (?, ?, '101호', 'ONE_ROOM', now(), now()) returning id",
				Long.class, landlord, property);
		LeaseTerms terms = new LeaseTerms(0, 25, LocalDate.parse("2026-01-01"), LocalDate.parse("2027-01-01"), "국민은행",
				new AccountNumber("1234567890"), new PersonName("김임대"), null);
		long lease = leaseService.register(landlord, new LeaseRegistration(unit, new PersonName("홍길동"),
				PhoneNumber.parse("01011112222"), null, 500_000, 0, terms, false));
		long tenant = jdbc.queryForObject("select tenant_id from lease where id = ?", Long.class, lease);
		long charge = jdbc.queryForObject("insert into charge (landlord_id, lease_id, billing_month, due_date, "
				+ "total_amount, status, source, issued_at, created_at, updated_at) values (?, ?, '2026-10-01', "
				+ "'2026-10-25', 500000, 'ISSUED', 'AUTO', now(), now(), now()) returning id", Long.class, landlord,
				lease);
		return new long[]{tenant, lease, charge};
	}

	private NotificationRequest tenantRequest(long[] keys, String dedupeKey) {
		return NotificationRequest.tenant(NotificationType.CHARGE_ISSUED, landlord, keys[0], keys[1], keys[2],
				dedupeKey);
	}

	private int count(String where) {
		return jdbc.queryForObject("select count(*) from notification where " + where, Integer.class);
	}

	/** "상태[:사유]" → 건수 */
	private Map<String, Integer> statuses() {
		Map<String, Integer> statuses = new java.util.TreeMap<>();
		jdbc.query("select status, skip_reason, count(*) c from notification group by status, skip_reason", rs -> {
			String key = rs.getString(1) + (rs.getString(2) == null ? "" : ":" + rs.getString(2));
			statuses.put(key, rs.getInt(3));
		});
		return statuses;
	}

}
