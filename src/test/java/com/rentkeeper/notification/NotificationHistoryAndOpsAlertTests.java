package com.rentkeeper.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

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
import com.rentkeeper.common.ops.LoggingOpsAlertNotifier;
import com.rentkeeper.common.ops.OpsAlertNotifier;
import com.rentkeeper.landlord.application.LandlordService;
import com.rentkeeper.lease.application.LeaseService;
import com.rentkeeper.lease.application.LeaseViews.LeaseRegistration;
import com.rentkeeper.lease.domain.AccountNumber;
import com.rentkeeper.lease.domain.LeaseTerms;
import com.rentkeeper.lease.domain.PersonName;
import com.rentkeeper.lease.domain.PhoneNumber;
import com.rentkeeper.notification.application.NotificationPlanner;
import com.rentkeeper.notification.application.NotificationWorker;
import com.rentkeeper.support.LandlordLogin;
import com.rentkeeper.support.LandlordLogin.Session;
import com.rentkeeper.support.MutableClock;
import com.rentkeeper.support.MutableClockConfiguration;
import com.rentkeeper.support.NotificationTestConfiguration;
import com.rentkeeper.support.NotificationTestConfiguration.MutableNotificationChannels;
import com.rentkeeper.support.NotificationTestConfiguration.RecordingNotificationSender;
import com.rentkeeper.support.NotificationTestConfiguration.TestComposer;
import com.rentkeeper.support.TestData;

/**
 * 운영 알림 메일 행과 알림 이력 화면 (M5 커밋 6, 결정 15·16, NOTI-7).
 * <ul>
 * <li>운영 알림: ERROR 로그는 그대로, 코드마다 하루 한 통. 부른 쪽이 롤백해도 남는다. 메일에는 코드만</li>
 * <li>이력: 최근 90일, 종류·받는 사람·상태·시각만(본문·번호 없음). 로그인한 임대인 것만</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, MutableClockConfiguration.class, NotificationTestConfiguration.class})
@ExtendWith(OutputCaptureExtension.class)
class NotificationHistoryAndOpsAlertTests {

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
	NotificationPlanner planner;

	@Autowired
	NotificationWorker worker;

	@Autowired
	OpsAlertNotifier opsAlerts;

	@Autowired
	PlatformTransactionManager transactionManager;

	@Autowired
	MutableClock clock;

	@Autowired
	RecordingNotificationSender sender;

	@Autowired
	MutableNotificationChannels channels;

	@Autowired
	TestComposer composer;

	Session a;

	@BeforeEach
	void setUp() throws Exception {
		clock.set(MutableClockConfiguration.START);
		TestData.reset(jdbc);
		NotificationTestConfiguration.reset(sender, channels, composer);
		a = LandlordLogin.signUpAndLogin(mockMvc, landlordService, passwordEncoder, "a@example.com");
	}

	@Test
	void opsAlertLogsAndQueuesOneMailPerCodePerDay(CapturedOutput output) {
		opsAlerts.alert("CHARGE_BATCH_ALERT", "job=generation failed=2");
		opsAlerts.alert("CHARGE_BATCH_ALERT", "job=generation failed=3");
		opsAlerts.alert("KAKAO_UNLINK_ALERT", "config=1");

		assertThat(output).contains(LoggingOpsAlertNotifier.LOGGER_NAME)
				.contains("CHARGE_BATCH_ALERT job=generation failed=3");
		assertThat(jdbc.queryForList("select dedupe_key from notification order by dedupe_key", String.class))
				.containsExactly("OPS_ALERT:CHARGE_BATCH_ALERT:2026-10-17", "OPS_ALERT:KAKAO_UNLINK_ALERT:2026-10-17");

		clock.set(Instant.parse("2026-10-17T14:00:00Z")); // 23:00 서울, 운영 알림은 시간 제한 없음
		assertThat(worker.runOnce().sent()).isEqualTo(2);
		assertThat(sender.sent()).allSatisfy(m -> {
			assertThat(m.to()).isEqualTo("ops@example.com");
			assertThat(m.body()).doesNotContain("failed=");
		});
		assertThat(sender.sent()).extracting(m -> m.subject()).contains("[운영] CHARGE_BATCH_ALERT 발생");

		clock.advance(Duration.ofDays(1));
		opsAlerts.alert("CHARGE_BATCH_ALERT", "job=generation failed=1");
		assertThat(jdbc.queryForObject("select count(*) from notification", Integer.class)).as("다음 날은 새로").isEqualTo(3);
	}

	@Test
	void opsAlertSurvivesTheCallersRollback() {
		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			opsAlerts.alert("CHARGE_BATCH_ALERT", "x=1");
			status.setRollbackOnly();
		});

		assertThat(jdbc.queryForObject("select count(*) from notification where type = 'OPS_ALERT'", Integer.class))
				.isEqualTo(1);
	}

	@Test
	void historyShowsTypeRecipientStatusAndTimeOnly() throws Exception {
		long lease = lease();
		jdbc.update("insert into charge (landlord_id, lease_id, billing_month, due_date, total_amount, status, source, "
				+ "issued_at, created_at, updated_at) values (?, ?, '2026-10-01', '2026-10-18', 500000, 'ISSUED', "
				+ "'MANUAL', now(), now(), now())", a.landlordId(), lease);
		planner.planTenantNotices(); // 채널 꺼짐 → 보내지 않음
		jdbc.update("insert into notification (landlord_id, type, channel, recipient_type, scheduled_for, dedupe_key, "
				+ "status, next_attempt_at, sent_at, created_at, updated_at) values (?, 'OVERDUE_DIGEST', 'EMAIL', "
				+ "'LANDLORD', '2026-10-17', 'k1', 'SENT', now(), ?, ?, ?)", a.landlordId(), ts("2026-10-17T00:05:00Z"),
				ts("2026-10-17T00:00:00Z"), ts("2026-10-17T00:05:00Z"));
		jdbc.update(
				"insert into notification (landlord_id, type, channel, recipient_type, scheduled_for, dedupe_key, "
						+ "status, next_attempt_at, created_at, updated_at) values (?, 'LEASE_END_DIGEST', 'EMAIL', "
						+ "'LANDLORD', '2026-07-01', 'old', 'EXPIRED', now(), ?, ?)",
				a.landlordId(), ts("2026-07-01T00:00:00Z"), ts("2026-07-01T00:00:00Z"));

		String page = mockMvc.perform(get("/app/notifications").cookie(a.cookie())).andReturn().getResponse()
				.getContentAsString().replaceAll("\\s+", " ");

		assertThat(page).contains("<span>납부일 전날 안내</span> · <span>행복빌라 101호 세입자 (문자)</span>")
				.contains("보내지 않음: 안내 문자 기능이 아직 준비 중이에요");
		assertThat(page).contains("<span>납부일 지남 요약</span> · <span>나 (메일)</span>").contains("보냈어요")
				.contains("10월 17일 09:05");
		assertThat(page).as("90일보다 오래된 것은 없다").doesNotContain("곧 끝나는 계약");
		assertThat(page).as("번호·이름은 없다").doesNotContain("01011112222").doesNotContain("010-1111-2222")
				.doesNotContain("홍길동");
	}

	@Test
	void emptyHistory() throws Exception {
		assertThat(mockMvc.perform(get("/app/notifications").cookie(a.cookie())).andReturn().getResponse()
				.getContentAsString()).contains("아직 알림이 없어요");
	}

	private long lease() {
		long property = jdbc.queryForObject("insert into property (landlord_id, name, created_at, updated_at) "
				+ "values (?, '행복빌라', now(), now()) returning id", Long.class, a.landlordId());
		long unit = jdbc.queryForObject(
				"insert into unit (landlord_id, property_id, name, type, created_at, "
						+ "updated_at) values (?, ?, '101호', 'ONE_ROOM', now(), now()) returning id",
				Long.class, a.landlordId(), property);
		LeaseTerms terms = new LeaseTerms(0, 18, LocalDate.parse("2026-10-01"), LocalDate.parse("2027-07-01"), "국민은행",
				new AccountNumber("1234567890"), new PersonName("김임대"), null);
		return leaseService.register(a.landlordId(), new LeaseRegistration(unit, new PersonName("홍길동"),
				PhoneNumber.parse("01011112222"), null, 500_000, 0, terms, false));
	}

	private static Timestamp ts(String instant) {
		return Timestamp.from(Instant.parse(instant));
	}

}
