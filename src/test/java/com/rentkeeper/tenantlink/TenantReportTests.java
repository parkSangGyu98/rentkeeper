package com.rentkeeper.tenantlink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.rentkeeper.TestcontainersConfiguration;
import com.rentkeeper.landlord.application.LandlordService;
import com.rentkeeper.lease.application.LeaseService;
import com.rentkeeper.lease.application.LeaseViews.LeaseRegistration;
import com.rentkeeper.lease.application.TenantLinkService;
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
 * 세입자 "입금했어요" 신고와 임대인 확인·반려 (M4 커밋 3, 결정 7·8·9·10·11·13). 10월분 550,000원, 납부일
 * 10/25, 오늘 10/17(서울). 9월분은 납부일(9/25)이 지난 상태.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, MutableClockConfiguration.class})
class TenantReportTests {

	private static final String REJECTED_NOTICE = "집주인이 아직 입금을 확인하지 못했어요. 이미 보내셨다면 집주인에게 직접 알려 주세요.";

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

	String token;

	long tokenId;

	long october;

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
		LeaseTerms terms = new LeaseTerms(0, 25, LocalDate.parse("2026-01-01"), LocalDate.parse("2027-01-01"), "국민은행",
				new AccountNumber("1234567890"), new PersonName("김임대"), null);
		lease = leaseService.register(a.landlordId(), new LeaseRegistration(unit, new PersonName("홍길동"),
				PhoneNumber.parse("01011112222"), null, 500_000, 50_000, terms, false));
		token = tenantLinkService.issue(a.landlordId(), lease, null).rawToken();
		tokenId = jdbc.queryForObject("select id from tenant_access_token", Long.class);
		october = charge("2026-10-01", "2026-10-25", "ISSUED");
		item(october, "RENT", "월세", 500_000);
		item(october, "MAINTENANCE", "관리비", 50_000);
	}

	@Test
	void reportingOnceAndAgainChangesStatusOnlyOnce() throws Exception {
		assertThat(tenantPage()).contains("href=\"/t/" + token + "/charges/" + october + "/report-paid\"");
		assertThat(page(get(reportPath(october)))).contains("<span>10월 월세</span> <span>550,000원</span>을 보내셨나요?")
				.contains("네, 보냈어요");

		assertThat(report(october).getResponse().getContentAsString()).contains("집주인에게 알렸어요");
		assertThat(status(october)).isEqualTo("TENANT_REPORTED");
		assertThat(jdbc.queryForMap("select actor_type, actor_id, reason from charge_status_history "
				+ "where charge_id = ? order by id desc limit 1", october)).containsEntry("actor_type", "TENANT")
				.containsEntry("actor_id", tokenId).containsEntry("reason", "세입자 입금 신고");

		MvcResult again = report(october);
		assertThat(again.getResponse().getStatus()).isEqualTo(200);
		assertThat(again.getResponse().getContentAsString()).as("다시 눌러도 같은 안내").contains("집주인에게 알렸어요");
		assertThat(reportCount(october)).as("상태 이력은 한 번").isEqualTo(1);

		assertThat(tenantPage()).contains("입금했다고 알려 주셨어요").doesNotContain("/report-paid\"");
		String detail = landlordPage("/app/charges/" + october);
		assertThat(detail).contains("세입자가 입금했다고 알렸어요").contains("1번째 신고").contains("2026년 10월 17일").contains("입금 확인하기")
				.contains("입금이 없어요 (반려)");
		assertThat(landlordPage("/app/charges?month=2026-10&status=reported")).contains("홍길동님")
				.contains(">확인 기다림 1</a>");
	}

	@Test
	void concurrentReportsAreRecordedOnce() throws Exception {
		int threads = 5;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<Integer>> results = new ArrayList<>();
		for (int i = 0; i < threads; i++) {
			results.add(pool.submit(() -> {
				start.await();
				return report(october).getResponse().getStatus();
			}));
		}
		start.countDown();
		for (Future<Integer> result : results) {
			assertThat(result.get()).isEqualTo(200);
		}
		pool.shutdown();

		assertThat(reportCount(october)).isEqualTo(1);
		assertThat(status(october)).isEqualTo("TENANT_REPORTED");
	}

	@Test
	void paidChargeSaysAlreadyConfirmedAndCanceledChargeIsNotFound() throws Exception {
		long september = charge("2026-09-01", "2026-09-25", "PAID");
		long august = charge("2026-08-01", "2026-08-25", "ISSUED");
		jdbc.update("update charge set status = 'CANCELED', canceled_at = now(), cancel_reason = '잘못' where id = ?",
				august);

		assertThat(page(get(reportPath(september)))).contains("이미 입금이 확인됐어요");
		assertThat(report(september).getResponse().getContentAsString()).contains("이미 입금이 확인됐어요");
		assertThat(status(september)).isEqualTo("PAID");

		assertThat(mockMvc.perform(get(reportPath(august))).andReturn().getResponse().getStatus()).isEqualTo(404);
		assertThat(report(august).getResponse().getStatus()).isEqualTo(404);
		assertThat(status(august)).isEqualTo("CANCELED");
	}

	@Test
	void reportFromAnotherSiteIsRefused() throws Exception {
		MvcResult crossSite = mockMvc.perform(post(reportPath(october)).header("Origin", "https://evil.example"))
				.andReturn();
		assertThat(crossSite.getResponse().getStatus()).isEqualTo(403);
		assertThat(crossSite.getResponse().getContentAsString()).contains("다시 열어 주세요");
		assertThat(status(october)).isEqualTo("ISSUED");

		assertThat(mockMvc.perform(post(reportPath(october)).header("Origin", "http://localhost:8080")).andReturn()
				.getResponse().getStatus()).as("같은 출처").isEqualTo(200);
		assertThat(status(october)).isEqualTo("TENANT_REPORTED");
	}

	@Test
	void fivePostsPerLinkPerHour() throws Exception {
		for (int i = 0; i < 5; i++) {
			assertThat(report(october).getResponse().getStatus()).isEqualTo(200);
		}

		MvcResult blocked = report(october);
		assertThat(blocked.getResponse().getStatus()).isEqualTo(429);
		assertThat(blocked.getResponse().getContentAsString()).contains("잠시 뒤에 열어 주세요");
		assertThat(reportCount(october)).isEqualTo(1);
	}

	@Test
	void landlordConfirmsTheReportAsAPayment() throws Exception {
		report(october);

		String form = landlordPage("/app/charges/" + october + "/payments/new?report=true");
		assertThat(form).contains("세입자 입금 확인").contains("<strong>2026년 10월 17일</strong>에 입금했다고 알렸어요")
				.contains("value=\"550000\"").contains("value=\"2026-10-17\"");

		MvcResult saved = mockMvc.perform(post("/app/charges/" + october + "/payments").with(csrf()).cookie(a.cookie())
				.param("version", String.valueOf(version(october))).param("amount", "550,000")
				.param("paidOn", "2026-10-17").param("memo", "").param("fromReport", "true")).andReturn();

		assertThat(saved.getResponse().getRedirectedUrl()).isEqualTo("/app/charges/" + october);
		assertThat(status(october)).isEqualTo("PAID");
		assertThat(jdbc.queryForObject("select source from payment_record where charge_id = ?", String.class, october))
				.isEqualTo("TENANT_REPORT");
		assertThat(jdbc.queryForObject(
				"select reason from charge_status_history where charge_id = ? " + "order by id desc limit 1",
				String.class, october)).isEqualTo("세입자 신고 확인");
		assertThat(tenantPage()).contains("입금이 확인됐어요").doesNotContain("/report-paid\"");
	}

	@Test
	void tenantReportMakesTheLandlordsOpenFormStale() throws Exception {
		long seen = version(october);

		report(october);

		MvcResult stale = mockMvc.perform(post("/app/charges/" + october + "/payments").with(csrf()).cookie(a.cookie())
				.param("version", String.valueOf(seen)).param("amount", "550,000").param("paidOn", "2026-10-17")
				.param("memo", "")).andReturn();
		assertThat(String.valueOf(stale.getFlashMap().get("chargeAlert"))).startsWith("이 월세 내용이 방금 바뀌었어요.");
		assertThat(jdbc.queryForObject("select count(*) from payment_record", Integer.class)).isZero();
	}

	@Test
	void rejectingShowsANeutralNoticeAndTheTenantCanReportAgain() throws Exception {
		report(october);
		assertThat(landlordPage("/app/charges/" + october + "/report/reject")).contains("세입자 신고를 반려할까요?")
				.contains("사유는 세입자에게 보이지 않아요");

		MvcResult blank = rejectResult(october, version(october), " ");
		assertThat(blank.getResponse().getContentAsString()).contains("반려 사유를 1~180자로 입력해 주세요 (예: 통장에 입금이 없음)");
		assertThat(status(october)).isEqualTo("TENANT_REPORTED");

		MvcResult rejected = rejectResult(october, version(october), "통장에 입금이 없음");
		assertThat(String.valueOf(rejected.getFlashMap().get("notice"))).startsWith("신고를 반려했어요. 지금 상태는 '받을 차례'예요.");
		assertThat(status(october)).isEqualTo("ISSUED");
		assertThat(jdbc.queryForMap("select actor_type, reason from charge_status_history where charge_id = ? "
				+ "order by id desc limit 1", october)).containsEntry("actor_type", "LANDLORD")
				.containsEntry("reason", "신고 반려: 통장에 입금이 없음");

		String tenant = tenantPage();
		assertThat(tenant).contains(REJECTED_NOTICE).as("사유는 세입자에게 보이지 않는다").doesNotContain("통장에 입금이 없음")
				.contains("/report-paid\"");

		report(october);
		assertThat(tenantPage()).doesNotContain(REJECTED_NOTICE).contains("입금했다고 알려 주셨어요");
		assertThat(landlordPage("/app/charges/" + october)).contains("2번째 신고");
	}

	@Test
	void rejectingAfterTheDueDateMakesItOverdue() throws Exception {
		long september = charge("2026-09-01", "2026-09-25", "OVERDUE");
		report(september);
		assertThat(status(september)).as("납부일이 지나도 신고할 수 있다").isEqualTo("TENANT_REPORTED");

		rejectResult(september, version(september), "통장에 입금이 없음");

		assertThat(status(september)).isEqualTo("OVERDUE");
	}

	@Test
	void rejectingWithAStaleVersionOrWithoutAReportChangesNothing() throws Exception {
		report(october);
		MvcResult stale = rejectResult(october, version(october) - 1, "통장에 입금이 없음");
		assertThat(String.valueOf(stale.getFlashMap().get("chargeAlert"))).startsWith("이 월세 내용이 방금 바뀌었어요.");
		assertThat(status(october)).isEqualTo("TENANT_REPORTED");

		long september = charge("2026-09-01", "2026-09-25", "OVERDUE");
		assertThat(String.valueOf(rejectResult(september, version(september), "사유").getFlashMap().get("chargeAlert")))
				.isEqualTo("세입자 신고가 이미 처리됐어요. 아래 최신 내용을 확인해 주세요.");
		assertThat(status(september)).isEqualTo("OVERDUE");
	}

	private String reportPath(long charge) {
		return "/t/" + token + "/charges/" + charge + "/report-paid";
	}

	private MvcResult report(long charge) throws Exception {
		return mockMvc.perform(post(reportPath(charge))).andReturn();
	}

	private MvcResult rejectResult(long charge, long version, String reason) throws Exception {
		return mockMvc.perform(post("/app/charges/" + charge + "/report/reject").with(csrf()).cookie(a.cookie())
				.param("version", String.valueOf(version)).param("reason", reason)).andReturn();
	}

	private String tenantPage() throws Exception {
		return page(get("/t/" + token));
	}

	private String landlordPage(String path) throws Exception {
		return page(get(path).cookie(a.cookie()));
	}

	private String page(MockHttpServletRequestBuilder request) throws Exception {
		return mockMvc.perform(request).andReturn().getResponse().getContentAsString().replaceAll("\\s+", " ");
	}

	private String status(long charge) {
		return jdbc.queryForObject("select status from charge where id = ?", String.class, charge);
	}

	private long version(long charge) {
		return jdbc.queryForObject("select version from charge where id = ?", Long.class, charge);
	}

	private int reportCount(long charge) {
		return jdbc.queryForObject(
				"select count(*) from charge_status_history where charge_id = ? " + "and to_status = 'TENANT_REPORTED'",
				Integer.class, charge);
	}

	private long charge(String month, String due, String status) {
		long paid = "PAID".equals(status) ? 550_000L : 0L;
		return jdbc.queryForObject("insert into charge (landlord_id, lease_id, billing_month, due_date, total_amount, "
				+ "paid_amount, status, source, issued_at, paid_at, created_at, updated_at) values (?, ?, cast(? as date), "
				+ "cast(? as date), 550000, ?, ?, 'AUTO', now(), case when ? = 'PAID' then now() end, now(), now()) "
				+ "returning id", Long.class, a.landlordId(), lease, month, due, paid, status, status);
	}

	private void item(long charge, String type, String label, long amount) {
		jdbc.update("insert into charge_item (landlord_id, charge_id, type, label, amount, created_at, updated_at) "
				+ "values (?, ?, ?, ?, ?, now(), now())", a.landlordId(), charge, type, label, amount);
	}

}
