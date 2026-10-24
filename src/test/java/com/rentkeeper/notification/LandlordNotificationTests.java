package com.rentkeeper.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import com.rentkeeper.TestcontainersConfiguration;
import com.rentkeeper.landlord.application.LandlordService;
import com.rentkeeper.landlord.domain.EmailAddress;
import com.rentkeeper.lease.application.LeaseService;
import com.rentkeeper.lease.application.LeaseViews.LeaseRegistration;
import com.rentkeeper.lease.application.TenantLinkService;
import com.rentkeeper.lease.domain.AccountNumber;
import com.rentkeeper.lease.domain.LeaseTerms;
import com.rentkeeper.lease.domain.PersonName;
import com.rentkeeper.lease.domain.PhoneNumber;
import com.rentkeeper.notification.application.NotificationPlanner;
import com.rentkeeper.notification.application.NotificationWorker;
import com.rentkeeper.notification.application.OutboundNotification;
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
 * 임대인 알림과 알림 설정 (M5 커밋 5, NOTI-4·5, 결정 4·12·13). 시작 2026-10-17 09:00 서울.
 * <ul>
 * <li>요약 메일만: 입금 신고는 시간당 1통, 납부일 지남·곧 끝나는 계약은 하루 1통. 계약 300건이어도 1통</li>
 * <li>인증한 이메일로만. 인증 전에 생긴 알림은 인증한 뒤에도 보내지 않는다</li>
 * <li>설정을 끄면 보내지 않음(LANDLORD_OFF). 세입자 문자 설정도 같다. 설정은 로그인한 임대인 것만 바뀐다</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, MutableClockConfiguration.class, NotificationTestConfiguration.class})
class LandlordNotificationTests {

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
	TenantLinkService tenantLinkService;

	@Autowired
	NotificationPlanner planner;

	@Autowired
	NotificationWorker worker;

	@Autowired
	MutableClock clock;

	@Autowired
	RecordingNotificationSender sender;

	@Autowired
	MutableNotificationChannels channels;

	@Autowired
	TestComposer composer;

	Session a;

	long property;

	int units;

	@BeforeEach
	void setUp() throws Exception {
		clock.set(MutableClockConfiguration.START);
		TestData.reset(jdbc);
		NotificationTestConfiguration.reset(sender, channels, composer);
		a = LandlordLogin.signUpAndLogin(mockMvc, landlordService, passwordEncoder, "a@example.com");
		landlordService.verifyEmail(a.landlordId(), new EmailAddress("a@example.com"));
		property = jdbc.queryForObject("insert into property (landlord_id, name, created_at, updated_at) "
				+ "values (?, '행복빌라', now(), now()) returning id", Long.class, a.landlordId());
		units = 0;
	}

	@Test
	void tenantReportsAreOneMailPerHour() throws Exception {
		long lease = lease(a.landlordId(), "01011112222", "2027-07-01");
		String token = tenantLinkService.issue(a.landlordId(), lease, null).rawToken();
		for (String due : new String[]{"2026-10-25", "2026-11-25", "2026-12-25"}) {
			long charge = charge(a.landlordId(), lease, due, "ISSUED");
			mockMvc.perform(post("/t/" + token + "/charges/" + charge + "/report-paid"));
		}

		assertThat(rows()).containsOnlyKeys("TENANT_REPORT_DIGEST:PENDING");
		assertThat(worker.runOnce().sent()).isEqualTo(1);
		OutboundNotification mail = sender.sent().get(0);
		assertThat(mail.to()).isEqualTo("a@example.com");
		assertThat(mail.subject()).isEqualTo("세입자가 입금했다고 알린 받을 월세가 3건 있어요");
		assertThat(mail.body()).contains("http://localhost:8080/app/dashboard").doesNotContain("홍길동")
				.doesNotContain("01011112222");

		clock.advance(Duration.ofHours(1));
		long later = charge(a.landlordId(), lease, "2027-01-25", "ISSUED");
		mockMvc.perform(post("/t/" + token + "/charges/" + later + "/report-paid"));
		assertThat(count("type = 'TENANT_REPORT_DIGEST'")).as("다음 시간 칸은 새 메일").isEqualTo(2);
	}

	@Test
	void reportAlreadyConfirmedBeforeSendingIsNotMailed() throws Exception {
		long lease = lease(a.landlordId(), "01011112222", "2027-07-01");
		String token = tenantLinkService.issue(a.landlordId(), lease, null).rawToken();
		long charge = charge(a.landlordId(), lease, "2026-10-25", "ISSUED");
		mockMvc.perform(post("/t/" + token + "/charges/" + charge + "/report-paid"));
		jdbc.update("update charge set status = 'PAID', paid_amount = total_amount, paid_at = now() where id = ?",
				charge);

		worker.runOnce();

		assertThat(rows()).containsOnlyKeys("TENANT_REPORT_DIGEST:SKIPPED:STALE");
	}

	@Test
	void threeHundredOverdueLeasesMakeOneDigest() {
		for (int i = 0; i < 300; i++) {
			long lease = lease(a.landlordId(), "010%08d".formatted(i), "2027-07-01");
			charge(a.landlordId(), lease, "2026-10-16", "OVERDUE");
		}

		assertThat(planner.planLandlordDigests().created()).isEqualTo(1);
		assertThat(planner.planLandlordDigests().created()).as("다시 돌아도 같다").isZero();
		planner.planTenantNotices();

		assertThat(count("type = 'OVERDUE_DIGEST'")).isEqualTo(1);
		assertThat(count("type = 'OVERDUE_NOTICE'")).as("세입자 알림은 계약당 하나").isEqualTo(300);
		assertThat(jdbc.queryForObject(
				"select count(distinct lease_id) from notification where type = 'OVERDUE_NOTICE'", Integer.class))
				.isEqualTo(300);
		worker.runOnce();
		assertThat(sender.sent()).filteredOn(m -> m.to().equals("a@example.com")).singleElement()
				.satisfies(m -> assertThat(m.subject()).isEqualTo("납부일이 지난 받을 월세 300건"));
	}

	@Test
	void leaseEndDigestListsUnitsWithoutTenantNames() {
		lease(a.landlordId(), "01000000001", "2026-11-16"); // 30일 뒤
		lease(a.landlordId(), "01000000002", "2027-01-15"); // 90일 뒤
		lease(a.landlordId(), "01000000003", "2026-11-17"); // 31일 뒤

		planner.planLandlordDigests();
		worker.runOnce();

		OutboundNotification mail = sender.sent().get(0);
		assertThat(mail.subject()).isEqualTo("곧 끝나는 계약이 2건 있어요");
		assertThat(mail.body()).contains("행복빌라 101호 — 11월 16일 (30일 뒤)").contains("행복빌라 102호 — 1월 15일 (90일 뒤)")
				.doesNotContain("103호").doesNotContain("홍길동");
	}

	@Test
	void unverifiedEmailIsSkippedAndNotSentAfterVerifying() throws Exception {
		Session b = LandlordLogin.signUpAndLogin(mockMvc, landlordService, passwordEncoder, "b@example.com");
		long bProperty = jdbc.queryForObject("insert into property (landlord_id, name, created_at, updated_at) "
				+ "values (?, '다른빌라', now(), now()) returning id", Long.class, b.landlordId());
		property = bProperty;
		charge(b.landlordId(), lease(b.landlordId(), "01000000001", "2027-07-01"), "2026-10-16", "OVERDUE");
		assertThat(page("/app/settings/notifications", b)).contains("이메일을 인증해야 알림을 보내요");

		planner.planLandlordDigests();
		landlordService.verifyEmail(b.landlordId(), new EmailAddress("b@example.com"));
		planner.planLandlordDigests();
		worker.runOnce();

		assertThat(rows()).containsOnlyKeys("OVERDUE_DIGEST:SKIPPED:EMAIL_UNVERIFIED");
		assertThat(sender.sent()).isEmpty();
	}

	@Test
	void turnedOffSettingsSkipLandlordMailsAndTenantTexts() throws Exception {
		channels.tenantPhone(true);
		charge(a.landlordId(), lease(a.landlordId(), "01000000001", "2027-07-01"), "2026-10-16", "OVERDUE");
		mockMvc.perform(post("/app/settings/notifications").with(csrf()).cookie(a.cookie()));

		planner.planLandlordDigests();
		planner.planTenantNotices();
		worker.runOnce();

		assertThat(rows()).containsOnlyKeys("OVERDUE_DIGEST:SKIPPED:LANDLORD_OFF",
				"OVERDUE_NOTICE:SKIPPED:LANDLORD_OFF");
		assertThat(jdbc.queryForObject("select count(*) from tenant_access_token", Integer.class)).isZero();
	}

	@Test
	void settingsTurnedOffAfterPlanningAreCheckedAgainWhenSending() throws Exception {
		charge(a.landlordId(), lease(a.landlordId(), "01000000001", "2027-07-01"), "2026-10-16", "OVERDUE");
		planner.planLandlordDigests();
		mockMvc.perform(post("/app/settings/notifications").with(csrf()).cookie(a.cookie()).param("tenantSms", "true"));

		worker.runOnce();

		assertThat(rows()).containsOnlyKeys("OVERDUE_DIGEST:SKIPPED:LANDLORD_OFF");
	}

	@Test
	void settingsBelongOnlyToTheLoggedInLandlord() throws Exception {
		Session b = LandlordLogin.signUpAndLogin(mockMvc, landlordService, passwordEncoder, "b@example.com");
		assertThat(page("/app/settings/notifications", a)).contains("name=\"tenantReport\" value=\"true\"")
				.doesNotContain("이메일을 인증해야 알림을 보내요");

		mockMvc.perform(post("/app/settings/notifications").with(csrf()).cookie(a.cookie()).param("landlordId",
				String.valueOf(b.landlordId())));

		assertThat(jdbc.queryForObject("select count(*) from notification_preference where landlord_id = ?",
				Integer.class, b.landlordId())).as("남의 ID를 보내도 B는 그대로").isZero();
		assertThat(jdbc.queryForObject("select tenant_report from notification_preference where landlord_id = ?",
				Boolean.class, a.landlordId())).isFalse();
		assertThat(page("/app/settings/notifications", b)).contains("checked");
		assertThat(mockMvc.perform(get("/app/settings/notifications")).andReturn().getResponse().getStatus())
				.as("로그인하지 않으면 볼 수 없다").isNotEqualTo(200);
	}

	/** 계약 2026-10-01 ~ 나가는 날, 받는 날 25일 */
	private long lease(long landlordId, String phone, String end) {
		long unit = jdbc.queryForObject(
				"insert into unit (landlord_id, property_id, name, type, created_at, "
						+ "updated_at) values (?, ?, ?, 'ONE_ROOM', now(), now()) returning id",
				Long.class, landlordId, property, (101 + units++) + "호");
		LeaseTerms terms = new LeaseTerms(0, 25, LocalDate.parse("2026-10-01"), LocalDate.parse(end), "국민은행",
				new AccountNumber("1234567890"), new PersonName("김임대"), null);
		return leaseService.register(landlordId, new LeaseRegistration(unit, new PersonName("홍길동"),
				PhoneNumber.parse(phone), null, 500_000, 0, terms, false));
	}

	private long charge(long landlordId, long lease, String due, String status) {
		LocalDate dueDate = LocalDate.parse(due);
		return jdbc.queryForObject("insert into charge (landlord_id, lease_id, billing_month, due_date, total_amount, "
				+ "status, source, issued_at, created_at, updated_at) values (?, ?, ?, ?, 500000, ?, 'MANUAL', now(), "
				+ "now(), now()) returning id", Long.class, landlordId, lease, dueDate.withDayOfMonth(1), dueDate,
				status);
	}

	private String page(String path, Session session) throws Exception {
		return mockMvc.perform(get(path).cookie(session.cookie())).andReturn().getResponse().getContentAsString();
	}

	private int count(String where) {
		return jdbc.queryForObject("select count(*) from notification where " + where, Integer.class);
	}

	private Map<String, Integer> rows() {
		Map<String, Integer> rows = new TreeMap<>();
		jdbc.query("select type, status, skip_reason, count(*) from notification group by 1, 2, 3", rs -> {
			rows.put(rs.getString(1) + ":" + rs.getString(2) + (rs.getString(3) == null ? "" : ":" + rs.getString(3)),
					rs.getInt(4));
		});
		return rows;
	}

}
