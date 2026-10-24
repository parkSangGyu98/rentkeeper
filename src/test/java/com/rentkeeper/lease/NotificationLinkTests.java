package com.rentkeeper.lease;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
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

import com.rentkeeper.TestcontainersConfiguration;
import com.rentkeeper.common.crypto.SecureTokens;
import com.rentkeeper.landlord.application.LandlordService;
import com.rentkeeper.lease.application.LeaseService;
import com.rentkeeper.lease.application.LeaseViews.LeaseRegistration;
import com.rentkeeper.lease.application.TenantLinkService;
import com.rentkeeper.lease.application.TenantLinkService.NotificationLink;
import com.rentkeeper.lease.application.TenantLinkService.NotificationLinkUnavailable;
import com.rentkeeper.lease.domain.AccountNumber;
import com.rentkeeper.lease.domain.LeaseTerms;
import com.rentkeeper.lease.domain.PersonName;
import com.rentkeeper.lease.domain.PhoneNumber;
import com.rentkeeper.support.LandlordLogin;
import com.rentkeeper.support.LandlordLogin.Session;
import com.rentkeeper.support.MutableClock;
import com.rentkeeper.support.MutableClockConfiguration;
import com.rentkeeper.support.TestData;

/**
 * 알림용 세입자 링크 (M5 커밋 3, 결정 7·8, ADR-029). 계약 2026-01-01 ~ 2027-01-01, 오늘
 * 2026-10-17.
 * <ul>
 * <li>알림 링크는 보낼 때마다 바뀌고, 임대인이 직접 만든 링크는 그대로다. 예전 알림 링크는 틀린 링크와 같은 화면</li>
 * <li>[링크 닫기]는 두 유형을 모두 닫고 알림을 멈춘다. [새 링크 만들기]로는 풀리지 않고 [알림 다시 켜기]로만 풀린다</li>
 * <li>닫은 이유를 남긴다. 수신 거부는 알림 링크가 바뀌어도 그대로다. 원문은 로그에 없다</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, MutableClockConfiguration.class})
@ExtendWith(OutputCaptureExtension.class)
class NotificationLinkTests {

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
	MutableClock clock;

	Session a;

	long lease;

	@BeforeEach
	void setUp() throws Exception {
		clock.set(MutableClockConfiguration.START);
		TestData.reset(jdbc);
		a = LandlordLogin.signUpAndLogin(mockMvc, landlordService, passwordEncoder, "a@example.com");
		long property = jdbc.queryForObject("insert into property (landlord_id, name, created_at, updated_at) "
				+ "values (?, '행복빌라', now(), now()) returning id", Long.class, a.landlordId());
		long unit = jdbc.queryForObject(
				"insert into unit (landlord_id, property_id, name, type, created_at, "
						+ "updated_at) values (?, ?, '101호', 'ONE_ROOM', now(), now()) returning id",
				Long.class, a.landlordId(), property);
		LeaseTerms terms = new LeaseTerms(0, 5, LocalDate.parse("2026-01-01"), LocalDate.parse("2027-01-01"), "국민은행",
				new AccountNumber("1234567890"), new PersonName("김임대"), null);
		lease = leaseService.register(a.landlordId(), new LeaseRegistration(unit, new PersonName("홍길동"),
				PhoneNumber.parse("01011112222"), null, 500_000, 0, terms, false));
	}

	@Test
	void notificationLinkRotatesAndLeavesTheLandlordsLinkAlone() throws Exception {
		String landlordLink = tenantLinkService.issue(a.landlordId(), lease, null).rawToken();
		String first = issueForNotification();
		String second = issueForNotification();

		String notFound = body("/t/" + SecureTokens.newToken());
		assertThat(status("/t/" + landlordLink)).as("임대인 링크는 그대로").isEqualTo(200);
		assertThat(status("/t/" + second)).isEqualTo(200);
		assertThat(status("/t/" + first)).isEqualTo(404);
		assertThat(body("/t/" + first)).as("예전 알림 링크는 틀린 링크와 같은 화면").isEqualTo(notFound)
				.contains("가장 최근에 받은 안내 문자의 링크를 열거나, 집주인에게 새 링크를 요청해 주세요");
		assertThat(jdbc.queryForList(
				"select purpose || ':' || coalesce(revoke_reason, 'OPEN') from tenant_access_token " + "order by id",
				String.class))
				.containsExactly("LANDLORD:OPEN", "NOTIFICATION:NOTIFICATION_ROTATED", "NOTIFICATION:OPEN");
	}

	@Test
	void landlordReissueRecordsItsReason() {
		tenantLinkService.issue(a.landlordId(), lease, null);
		tenantLinkService.issue(a.landlordId(), lease, openId("LANDLORD"));

		assertThat(jdbc.queryForList("select revoke_reason from tenant_access_token where revoked_at is not null",
				String.class)).containsExactly("LANDLORD_REISSUED");
	}

	@Test
	void concurrentNotificationLinksLeaveOneOpen() throws Exception {
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(4);
		try {
			List<Future<NotificationLink>> runs = new ArrayList<>();
			for (int i = 0; i < 4; i++) {
				runs.add(pool.submit(() -> {
					start.await();
					return tenantLinkService.issueForNotification(a.landlordId(), lease);
				}));
			}
			start.countDown();
			for (Future<NotificationLink> run : runs) {
				assertThat(run.get().link()).isNotNull();
			}
		} finally {
			pool.shutdown();
		}

		assertThat(jdbc.queryForObject(
				"select count(*) from tenant_access_token where purpose = 'NOTIFICATION' " + "and revoked_at is null",
				Integer.class)).isEqualTo(1);
	}

	@Test
	void closingClosesBothLinksAndPausesUntilTurnedBackOn() throws Exception {
		tenantLinkService.issue(a.landlordId(), lease, null);
		String notificationLink = issueForNotification();
		assertThat(page("/app/leases/" + lease + "/tenant-link/close")).contains("세입자 안내 문자도 멈춰요")
				.contains("[알림 다시 켜기]");

		mockMvc.perform(post("/app/leases/" + lease + "/tenant-link/close").with(csrf()).cookie(a.cookie())
				.param("openLinkId", String.valueOf(openId("LANDLORD"))));

		assertThat(jdbc.queryForList("select revoke_reason from tenant_access_token order by id", String.class))
				.containsExactly("LANDLORD_CLOSED", "LANDLORD_CLOSED");
		assertThat(status("/t/" + notificationLink)).isEqualTo(404);
		assertThat(tenantLinkService.issueForNotification(a.landlordId(), lease).unavailable())
				.isEqualTo(NotificationLinkUnavailable.PAUSED_BY_LANDLORD);
		assertThat(tenantLinkService.notificationLinkUnavailable(a.landlordId(), lease))
				.contains(NotificationLinkUnavailable.PAUSED_BY_LANDLORD);
		assertThat(page("/app/leases/" + lease)).contains("세입자 안내 문자를 멈췄어요").contains("알림 다시 켜기");

		tenantLinkService.issue(a.landlordId(), lease, null);
		assertThat(tenantLinkService.issueForNotification(a.landlordId(), lease).unavailable())
				.as("[새 링크 만들기]로는 풀리지 않는다").isEqualTo(NotificationLinkUnavailable.PAUSED_BY_LANDLORD);
		assertThat(openCount("NOTIFICATION")).isZero();

		mockMvc.perform(post("/app/leases/" + lease + "/tenant-notifications/resume").with(csrf()).cookie(a.cookie()));

		assertThat(page("/app/leases/" + lease)).doesNotContain("세입자 안내 문자를 멈췄어요");
		assertThat(tenantLinkService.issueForNotification(a.landlordId(), lease).link()).isNotNull();
	}

	@Test
	void onlyANotificationLinkCanAlsoBeClosed() throws Exception {
		issueForNotification();
		assertThat(page("/app/leases/" + lease)).contains("안내 문자에 넣은 링크가 열려 있어요");

		mockMvc.perform(post("/app/leases/" + lease + "/tenant-link/close").with(csrf()).cookie(a.cookie())
				.param("openLinkId", ""));

		assertThat(openCount("NOTIFICATION")).isZero();
		assertThat(jdbc.queryForObject("select tenant_notify_paused_at is not null from lease where id = ?",
				Boolean.class, lease)).isTrue();
	}

	@Test
	void noNotificationLinkAfterThePeriod() {
		clock.set(Instant.parse("2027-04-01T15:00:00Z")); // 2027-04-02 서울 = 끝 + 91일

		assertThat(tenantLinkService.issueForNotification(a.landlordId(), lease).unavailable())
				.isEqualTo(NotificationLinkUnavailable.PERIOD_OVER);
		assertThat(openCount("NOTIFICATION")).isZero();
	}

	@Test
	void optOutSurvivesTheNotificationLinkRotating() throws Exception {
		String first = issueForNotification();
		mockMvc.perform(post("/t/" + first + "/optout"));

		String second = issueForNotification();

		assertThat(body("/t/" + second)).contains("안내 문자를 받지 않기로 하셨어요");
	}

	@Test
	void rawNotificationTokensNeverReachTheLogs(CapturedOutput output) {
		NotificationLink link = tenantLinkService.issueForNotification(a.landlordId(), lease);

		assertThat(link.toString()).doesNotContain(link.link().rawToken());
		assertThat(link.link().toString()).doesNotContain(link.link().rawToken());
		assertThat(output).doesNotContain(link.link().rawToken());
		assertThat(jdbc.queryForObject("select count(*) from tenant_access_token where token_hash = ?", Integer.class,
				link.link().rawToken())).isZero();
	}

	private String issueForNotification() {
		return tenantLinkService.issueForNotification(a.landlordId(), lease).link().rawToken();
	}

	private long openId(String purpose) {
		return jdbc.queryForObject("select id from tenant_access_token where purpose = ? and revoked_at is null",
				Long.class, purpose);
	}

	private int openCount(String purpose) {
		return jdbc.queryForObject("select count(*) from tenant_access_token where purpose = ? and revoked_at is null",
				Integer.class, purpose);
	}

	private int status(String path) throws Exception {
		return mockMvc.perform(get(path)).andReturn().getResponse().getStatus();
	}

	private String body(String path) throws Exception {
		return mockMvc.perform(get(path)).andReturn().getResponse().getContentAsString().replaceAll("\\s+", " ");
	}

	private String page(String path) throws Exception {
		return mockMvc.perform(get(path).cookie(a.cookie())).andReturn().getResponse().getContentAsString()
				.replaceAll("\\s+", " ");
	}

}
