package com.rentkeeper.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
import com.rentkeeper.billing.application.ChargeGenerator;
import com.rentkeeper.landlord.application.LandlordService;
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
import com.rentkeeper.support.MutableClock;
import com.rentkeeper.support.MutableClockConfiguration;
import com.rentkeeper.support.NotificationTestConfiguration;
import com.rentkeeper.support.NotificationTestConfiguration.MutableNotificationChannels;
import com.rentkeeper.support.NotificationTestConfiguration.RecordingNotificationSender;
import com.rentkeeper.support.NotificationTestConfiguration.TestComposer;
import com.rentkeeper.support.TestData;

/**
 * 세입자 알림 3종 (M5 커밋 4, NOTI-1~3, 결정 4·5·7·9·12). 시작 2026-10-17 09:00 서울.
 * <ul>
 * <li>받을 월세 안내는 납부일이 지나지 않은 발행분에만: 누락 복구분에는 없다</li>
 * <li>전날 안내, D+1·D+7 안내. 세입자 신고 상태는 빠진다. 계약당 하루 하나</li>
 * <li>보낼 때 다시 확인(상태·수신 거부·알림 멈춤·하루 1통). 보내지 않는 알림에는 링크를 만들지 않는다</li>
 * <li>보낸 알림의 링크는 알림 링크이고 열린다. 번호·본문·링크 원문은 DB에 없다</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, MutableClockConfiguration.class, NotificationTestConfiguration.class})
class TenantNotificationTests {

	private static final Pattern LINK = Pattern.compile("http://localhost:8080/t/([A-Za-z0-9_-]{43})");

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
	ChargeGenerator chargeGenerator;

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

	long landlord;

	long property;

	int units;

	@BeforeEach
	void setUp() throws Exception {
		clock.set(MutableClockConfiguration.START);
		TestData.reset(jdbc);
		NotificationTestConfiguration.reset(sender, channels, composer);
		channels.tenantPhone(true);
		landlord = LandlordLogin.signUpAndLogin(mockMvc, landlordService, passwordEncoder, "a@example.com")
				.landlordId();
		property = jdbc.queryForObject("insert into property (landlord_id, name, created_at, updated_at) "
				+ "values (?, '행복빌라', now(), now()) returning id", Long.class, landlord);
		units = 0;
	}

	@Test
	void backfilledChargesGetNoNoticeOnlyTheCurrentOneDoes() {
		long lease = lease("01011112222");
		clock.set(Instant.parse("2026-12-20T00:00:00Z")); // 배치가 두 달 못 돈 뒤

		assertThat(chargeGenerator.run().created()).isEqualTo(3);

		assertThat(jdbc.queryForList(
				"select c.billing_month::text from notification n join charge c on c.id = n.charge_id", String.class))
				.as("10·11월(납부일 지남)은 안내 없음").containsExactly("2026-12-01");
		assertThat(rows()).containsOnlyKeys("CHARGE_ISSUED:PENDING");
		assertThat(lease).isPositive();
	}

	@Test
	void chargeIssuedNoticeIsSentWithAFreshNotificationLink() throws Exception {
		lease("01011112222");
		clock.set(Instant.parse("2026-10-18T00:00:00Z")); // 10-18 09:00, 10월분 만드는 날(납부일 7일 전)
		chargeGenerator.run();

		assertThat(worker.runOnce().sent()).isEqualTo(1);

		OutboundNotification message = sender.sent().get(0);
		assertThat(message.to()).isEqualTo("01011112222");
		assertThat(message.body()).startsWith("[행복빌라 101호] 10월 월세 안내드립니다. 보내실 금액 500,000원, 보내실 날짜 10월 25일까지입니다.")
				.doesNotContain("1234567890").doesNotContain("홍길동").doesNotContain("김임대");
		Matcher link = LINK.matcher(message.body());
		assertThat(link.find()).isTrue();
		assertThat(mockMvc.perform(get("/t/" + link.group(1))).andReturn().getResponse().getStatus()).isEqualTo(200);
		assertThat(jdbc.queryForList("select purpose from tenant_access_token", String.class))
				.containsExactly("NOTIFICATION");
		assertThat(jdbc.queryForObject("select count(*) from notification n where n::text like '%0101111%' "
				+ "or n::text like ? or n::text like '%월세 안내%'", Integer.class, "%" + link.group(1) + "%"))
				.as("번호·링크 원문·본문은 저장하지 않는다").isZero();
	}

	@Test
	void reminderTheDayBeforeAndOverdueOnDayOneAndSeven() {
		long remind = charge(lease("01000000001"), "2026-10-18", "ISSUED");
		long reported = charge(lease("01000000002"), "2026-10-16", "TENANT_REPORTED");
		long d1 = charge(lease("01000000003"), "2026-10-16", "OVERDUE");
		long d3 = charge(lease("01000000004"), "2026-10-14", "OVERDUE");
		long d7 = charge(lease("01000000005"), "2026-10-10", "OVERDUE");
		long today = charge(lease("01000000006"), "2026-10-17", "ISSUED");

		assertThat(planner.planTenantNotices().created()).isEqualTo(3);
		assertThat(planner.planTenantNotices().created()).as("다시 돌아도 같다").isZero();

		assertThat(jdbc.queryForList("select dedupe_key from notification order by dedupe_key", String.class))
				.containsExactly("DUE_REMINDER:" + remind, "OVERDUE_NOTICE:" + d1 + ":D1",
						"OVERDUE_NOTICE:" + d7 + ":D7");
		assertThat(List.of(reported, d3, today)).allSatisfy(id -> assertThat(
				jdbc.queryForObject("select count(*) from notification where charge_id = ?", Integer.class, id))
				.isZero());

		worker.runOnce();
		assertThat(sender.sent()).extracting(OutboundNotification::body)
				.anySatisfy(b -> assertThat(b).contains("내일(10월 18일)은 10월 월세를 보내시는 날입니다"))
				.anySatisfy(b -> assertThat(b).contains("10월 월세 입금이 아직 확인되지 않았습니다"))
				.anySatisfy(b -> assertThat(b).contains("10월 월세 500,000원의 입금 확인을 기다리고 있습니다"));
	}

	@Test
	void plannerDoesNotCatchUpMissedDays() {
		charge(lease("01000000001"), "2026-10-15", "OVERDUE"); // D+2: 어제 계획이 빠졌다

		assertThat(planner.planTenantNotices().created()).isZero();
	}

	@Test
	void oneNoticePerLeasePerDay() {
		long lease = lease("01000000001");
		long september = charge(lease, "2026-09-16", "OVERDUE");
		jdbc.update("update charge set due_date = '2026-10-16' where id = ?", september); // D+1
		charge(lease, "2026-10-18", "ISSUED"); // 전날 안내도 해당

		planner.planTenantNotices();

		assertThat(jdbc.queryForList("select type from notification", String.class)).as("지남 > 전날")
				.containsExactly("OVERDUE_NOTICE");
	}

	@Test
	void sendingRechecksTheChargeAndMakesNoLinkWhenSkipped() {
		long paid = charge(lease("01000000001"), "2026-10-18", "ISSUED");
		long reported = charge(lease("01000000002"), "2026-10-18", "ISSUED");
		planner.planTenantNotices();
		jdbc.update("update charge set status = 'PAID', paid_amount = total_amount, paid_at = now() where id = ?",
				paid);
		jdbc.update("update charge set status = 'TENANT_REPORTED', tenant_reported_at = now() where id = ?", reported);

		assertThat(worker.runOnce().skipped()).isEqualTo(2);

		assertThat(rows()).containsOnlyKeys("DUE_REMINDER:SKIPPED:STALE");
		assertThat(sender.sent()).isEmpty();
		assertThat(jdbc.queryForObject("select count(*) from tenant_access_token", Integer.class))
				.as("보내지 않는 알림에는 링크를 만들지 않는다").isZero();
	}

	@Test
	void secondNoticeTheSameDayIsCapped() {
		long lease = lease("01000000001");
		charge(lease, "2026-10-18", "ISSUED");
		planner.planTenantNotices();
		long another = charge(lease, "2026-11-18", "ISSUED");
		jdbc.update("insert into notification (landlord_id, type, channel, recipient_type, tenant_id, lease_id, "
				+ "charge_id, scheduled_for, dedupe_key, status, next_attempt_at, created_at, updated_at) select "
				+ "landlord_id, 'CHARGE_ISSUED', 'TENANT_PHONE', 'TENANT', tenant_id, lease_id, ?, scheduled_for, "
				+ "'CHARGE_ISSUED:' || ?, 'PENDING', next_attempt_at, now(), now() from notification", another,
				another);

		worker.runOnce();

		assertThat(rows()).containsEntry("DUE_REMINDER:SENT", 1).containsEntry("CHARGE_ISSUED:SKIPPED:DAILY_CAP", 1);
		assertThat(jdbc.queryForObject("select count(*) from tenant_access_token", Integer.class)).isEqualTo(1);
	}

	@Test
	void optedOutTenantGetsNothingAtPlanningOrSending() throws Exception {
		long planned = lease("01000000001");
		charge(planned, "2026-10-18", "ISSUED");
		planner.planTenantNotices();
		long early = lease("01000000002");
		charge(early, "2026-10-18", "ISSUED");
		optOut(planned);
		optOut(early);
		jdbc.update("delete from notification where lease_id = ?", early);

		planner.planTenantNotices();
		worker.runOnce();

		assertThat(rows()).containsOnlyKeys("DUE_REMINDER:SKIPPED:OPTED_OUT")
				.containsEntry("DUE_REMINDER:SKIPPED:OPTED_OUT", 2);
		assertThat(sender.sent()).isEmpty();
	}

	@Test
	void pausedLeaseGetsNothingAndNoLink() {
		long paused = lease("01000000001");
		charge(paused, "2026-10-18", "ISSUED");
		planner.planTenantNotices();
		jdbc.update("update lease set tenant_notify_paused_at = now() where id = ?", paused);

		worker.runOnce();

		assertThat(rows()).containsOnlyKeys("DUE_REMINDER:SKIPPED:LINK_CLOSED_BY_LANDLORD");
		assertThat(jdbc.queryForObject("select count(*) from tenant_access_token", Integer.class)).isZero();
	}

	@Test
	void channelOffMakesNoLink() {
		channels.tenantPhone(false);
		charge(lease("01000000001"), "2026-10-18", "ISSUED");

		planner.planTenantNotices();
		worker.runOnce();

		assertThat(rows()).containsOnlyKeys("DUE_REMINDER:SKIPPED:CHANNEL_OFF");
		assertThat(jdbc.queryForObject("select count(*) from tenant_access_token", Integer.class)).isZero();
	}

	private void optOut(long lease) throws Exception {
		String token = tenantLinkService.issue(landlord, lease, null).rawToken();
		mockMvc.perform(post("/t/" + token + "/optout"));
	}

	/** 계약 2026-10-01 ~ 2027-07-01, 받는 날 25일, 납부일 7일 전에 만듦 */
	private long lease(String phone) {
		long unit = jdbc.queryForObject(
				"insert into unit (landlord_id, property_id, name, type, created_at, "
						+ "updated_at) values (?, ?, ?, 'ONE_ROOM', now(), now()) returning id",
				Long.class, landlord, property, (101 + units++) + "호");
		LeaseTerms terms = new LeaseTerms(0, 25, LocalDate.parse("2026-10-01"), LocalDate.parse("2027-07-01"), "국민은행",
				new AccountNumber("1234567890"), new PersonName("김임대"), null);
		return leaseService.register(landlord, new LeaseRegistration(unit, new PersonName("홍길동"),
				PhoneNumber.parse(phone), null, 500_000, 0, terms, false));
	}

	private long charge(long lease, String due, String status) {
		LocalDate dueDate = LocalDate.parse(due);
		return jdbc.queryForObject("insert into charge (landlord_id, lease_id, billing_month, due_date, total_amount, "
				+ "status, source, issued_at, tenant_reported_at, created_at, updated_at) values (?, ?, ?, ?, 500000, ?, "
				+ "'MANUAL', now(), case when ? = 'TENANT_REPORTED' then now() end, now(), now()) returning id",
				Long.class, landlord, lease, dueDate.withDayOfMonth(1), dueDate, status, status);
	}

	/** "종류:상태[:사유]" → 건수 */
	private Map<String, Integer> rows() {
		Map<String, Integer> rows = new TreeMap<>();
		jdbc.query("select type, status, skip_reason, count(*) from notification group by 1, 2, 3", rs -> {
			rows.put(rs.getString(1) + ":" + rs.getString(2) + (rs.getString(3) == null ? "" : ":" + rs.getString(3)),
					rs.getInt(4));
		});
		return rows;
	}

}
