package com.rentkeeper.billing;

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
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.rentkeeper.TestcontainersConfiguration;
import com.rentkeeper.billing.application.ChargeExceptions.ChargeChangedException;
import com.rentkeeper.billing.application.ChargeOverdueMarker;
import com.rentkeeper.billing.application.PaymentService;
import com.rentkeeper.billing.application.PaymentService.PaymentInput;
import com.rentkeeper.landlord.application.LandlordService;
import com.rentkeeper.lease.application.LeaseService;
import com.rentkeeper.lease.application.LeaseViews.LeaseRegistration;
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
 * 입금 기록 (M3-b 커밋 1): [다 받았어요]·[일부 받았어요], 입력 안내 문구, 초과 입금 확인, 낙관적 락 안내(다른 창·납부일
 * 지남 배치·두 번 누르기·동시 입금). 오늘은 2026-10-17(서울), 계약은 2026-01-01 들어옴.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, MutableClockConfiguration.class})
class PaymentTests {

	static final String CHANGED = "이 월세 내용이 방금 바뀌었어요. 다른 창에서 고쳤거나, 납부일이 지나 상태가 자동으로 바뀐 경우예요. "
			+ "아래 최신 내용을 확인하고, 필요하면 다시 기록해 주세요.";

	static final String ALREADY_PAID = "이미 다 받은 것으로 기록돼 있어요. 아래 입금 기록을 확인해 주세요.";

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
	PaymentService paymentService;

	@Autowired
	ChargeOverdueMarker overdueMarker;

	@Autowired
	MutableClock clock;

	Session a;

	long lease;

	long charge;

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
		// 10월분 받을 월세 550,000원, 납부일 10/25
		charge = jdbc.queryForObject("insert into charge (landlord_id, lease_id, billing_month, due_date, "
				+ "total_amount, status, source, issued_at, created_at, updated_at) values (?, ?, '2026-10-01', "
				+ "'2026-10-25', 550000, 'ISSUED', 'AUTO', now(), now(), now()) returning id", Long.class,
				a.landlordId(), lease);
		jdbc.update("insert into charge_item (landlord_id, charge_id, type, label, amount, created_at, updated_at) "
				+ "values (?, ?, 'RENT', '월세', 500000, now(), now()), (?, ?, 'MAINTENANCE', '관리비', 50000, now(), now())",
				a.landlordId(), charge, a.landlordId(), charge);
	}

	@Test
	void fullPaymentGoesThroughConfirmationAndMarksPaid() throws Exception {
		String form = page(get("/app/charges/" + charge + "/payments/new").param("full", "true"));
		assertThat(form).contains("다 받았어요").contains("550,000원").contains("value=\"2026-10-17\"")
				.contains("name=\"version\" value=\"0\"");

		MvcResult result = pay("550000", "2026-10-17", "0", true, false);
		assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/app/charges/" + charge);
		assertThat(result.getFlashMap().get("notice")).isEqualTo("입금을 기록했어요. 이 달 월세를 다 받았어요.");

		assertThat(jdbc.queryForMap(
				"select status, paid_amount, paid_at is not null as has_paid_at from charge where id = ?", charge))
				.containsEntry("status", "PAID").containsEntry("paid_amount", 550_000L)
				.containsEntry("has_paid_at", true);
		assertThat(jdbc.queryForMap("select amount, paid_on, source from payment_record where charge_id = ?", charge))
				.containsEntry("amount", 550_000L).containsEntry("source", "MANUAL");
		assertThat(jdbc.queryForMap("select from_status, to_status, actor_type, actor_id, reason "
				+ "from charge_status_history where charge_id = ?", charge)).containsEntry("from_status", "ISSUED")
				.containsEntry("to_status", "PAID").containsEntry("actor_type", "LANDLORD")
				.containsEntry("actor_id", a.landlordId()).containsEntry("reason", "입금 기록");
		assertThat(page(get("/app/charges/" + charge))).contains("다 받음").doesNotContain(">다 받았어요</a>");
	}

	@Test
	void partialPaymentsAddUpToPaid() throws Exception {
		MvcResult first = pay("200,000", "2026-10-10", "0", false, false);
		assertThat(first.getFlashMap().get("notice")).isEqualTo("입금을 기록했어요. 남은 금액은 350,000원이에요.");
		assertThat(status()).isEqualTo("PARTIALLY_PAID");

		pay("350000", "2026-10-17", "1", false, false);
		assertThat(status()).isEqualTo("PAID");
		assertThat(page(get("/app/charges/" + charge))).contains("200,000원").contains("2026년 10월 10일 받음")
				.contains("350,000원");
	}

	@Test
	void inputGuidesForZeroNegativeFutureTooLargeAndTooEarly() throws Exception {
		assertGuide("0", "2026-10-17", "받은 금액을 1원 이상 입력해 주세요 (예: 500,000)");
		assertGuide("-1000", "2026-10-17", "받은 금액을 숫자로 입력해 주세요 (예: 500,000)");
		assertGuide("오십만", "2026-10-17", "받은 금액을 숫자로 입력해 주세요 (예: 500,000)");
		assertGuide("1,000,000,001", "2026-10-17", "한 번에 기록할 수 있는 금액은 10억 원까지예요");
		assertGuide("1000", "2026-10-18", "받은 날은 오늘이나 그 전 날짜로 골라 주세요");
		assertGuide("1000", "2025-12-31", "받은 날은 계약이 시작된 달(2026년 1월 1일) 이후로 골라 주세요");
		assertGuide("1000", "", "받은 날을 골라 주세요");
		assertThat(jdbc.queryForObject("select count(*) from payment_record", Integer.class)).isZero();

		// 하한·상한 경계는 기록된다
		pay("1000000000", "2026-01-01", "0", false, true);
		assertThat(jdbc.queryForObject("select count(*) from payment_record", Integer.class)).isEqualTo(1);
	}

	@Test
	void overpaymentAsksOnceMoreThenRecordsAndShowsExtra() throws Exception {
		String ask = new String(pay("600,000", "2026-10-17", "0", false, false).getResponse().getContentAsByteArray(),
				"UTF-8").replaceAll("\\s+", " ");
		assertThat(ask).contains("남은 금액보다 50,000원 많아요. 이대로 기록할까요?")
				.contains("name=\"overpayConfirmed\" value=\"true\"");
		assertThat(jdbc.queryForObject("select count(*) from payment_record", Integer.class)).isZero();

		pay("600,000", "2026-10-17", "0", false, true);
		assertThat(status()).isEqualTo("PAID");
		assertThat(page(get("/app/charges/" + charge))).contains("더 받은 돈").contains("50,000원");
	}

	@Test
	void staleFormFromAnotherWindowShowsChangedGuide() throws Exception {
		pay("100000", "2026-10-17", "0", false, false);
		page(get("/app/charges/" + charge)); // 브라우저처럼 리다이렉트를 따라가 첫 안내를 본다

		MvcResult stale = pay("100000", "2026-10-17", "0", false, false);
		assertThat(stale.getResponse().getRedirectedUrl()).isEqualTo("/app/charges/" + charge);
		assertThat(stale.getFlashMap().get("chargeAlert")).isEqualTo(CHANGED);
		assertThat(jdbc.queryForObject("select count(*) from payment_record", Integer.class)).isEqualTo(1);
		// 같은 세션으로 상세를 열면(브라우저의 리다이렉트) 빨간 안내가 보인다. 한 번만 보인다
		assertThat(page(get("/app/charges/" + charge))).contains("role=\"alert\"").contains(CHANGED);
		assertThat(page(get("/app/charges/" + charge))).doesNotContain(CHANGED);
	}

	@Test
	void pressingFullPaymentTwiceShowsAlreadyPaid() throws Exception {
		pay("550000", "2026-10-17", "0", true, false);
		MvcResult again = pay("550000", "2026-10-17", "0", true, false);

		assertThat(again.getFlashMap().get("chargeAlert")).isEqualTo(ALREADY_PAID);
		assertThat(jdbc.queryForObject("select count(*) from payment_record", Integer.class)).isEqualTo(1);
		assertThat(page(get("/app/charges/" + charge + "/payments/new").param("full", "true")))
				.as("다 받은 청구는 입금 화면 대신 상세로").isEmpty();
	}

	@Test
	void overdueBatchBetweenFormAndSubmitShowsChangedGuide() throws Exception {
		clock.set(java.time.Instant.parse("2026-10-25T15:10:00Z")); // 서울 10/26 00:10
		overdueMarker.run();
		assertThat(status()).isEqualTo("OVERDUE");

		MvcResult result = pay("550000", "2026-10-26", "0", true, false);
		assertThat(result.getFlashMap().get("chargeAlert")).asString().contains("납부일이 지나 상태가 자동으로 바뀐 경우예요");
		assertThat(status()).isEqualTo("OVERDUE");
	}

	@Test
	void twoConcurrentPaymentsWithSameVersionRecordOnlyOne() throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			CountDownLatch start = new CountDownLatch(1);
			List<Future<String>> results = new ArrayList<>();
			for (int i = 0; i < 2; i++) {
				results.add(executor.submit(() -> {
					start.await();
					try {
						paymentService.record(a.landlordId(), charge, 0,
								new PaymentInput(200_000, LocalDate.parse("2026-10-17"), null, false));
						return "ok";
					} catch (ChargeChangedException | OptimisticLockingFailureException e) {
						return "changed";
					}
				}));
			}
			start.countDown();
			List<String> outcomes = new ArrayList<>();
			for (Future<String> result : results) {
				outcomes.add(result.get(30, TimeUnit.SECONDS));
			}
			assertThat(outcomes).containsExactlyInAnyOrder("ok", "changed");
		} finally {
			executor.shutdownNow();
		}
		assertThat(jdbc.queryForObject("select count(*) from payment_record", Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForMap("select paid_amount, version from charge where id = ?", charge))
				.containsEntry("paid_amount", 200_000L).containsEntry("version", 1L);
	}

	@Test
	void canceledChargeCannotTakePayments() throws Exception {
		jdbc.update("update charge set status = 'CANCELED', canceled_at = now(), cancel_reason = '잘못 만듦' where id = ?",
				charge);

		assertThat(mockMvc.perform(get("/app/charges/" + charge + "/payments/new").cookie(a.cookie())).andReturn()
				.getFlashMap().get("notice")).isEqualTo("취소한 받을 월세는 고칠 수 없어요.");
		MvcResult result = pay("1000", "2026-10-17", "0", false, false);
		assertThat(result.getFlashMap().get("chargeAlert")).isEqualTo("취소한 받을 월세는 고칠 수 없어요.");
		assertThat(jdbc.queryForObject("select count(*) from payment_record", Integer.class)).isZero();
	}

	@Test
	void eachPaymentBumpsVersionByExactlyOne() {
		paymentService.record(a.landlordId(), charge, 0,
				new PaymentInput(550_000, LocalDate.parse("2026-10-17"), null, false));
		assertThat(jdbc.queryForObject("select version from charge where id = ?", Long.class, charge)).isEqualTo(1);
	}

	private void assertGuide(String amount, String paidOn, String guide) throws Exception {
		String body = new String(pay(amount, paidOn, "0", false, false).getResponse().getContentAsByteArray(), "UTF-8");
		assertThat(body).as(amount + " / " + paidOn).contains(guide);
	}

	private MvcResult pay(String amount, String paidOn, String version, boolean full, boolean overpayConfirmed)
			throws Exception {
		return mockMvc.perform(post("/app/charges/" + charge + "/payments").with(csrf()).cookie(a.cookie())
				.param("amount", amount).param("paidOn", paidOn).param("memo", "").param("version", version)
				.param("full", String.valueOf(full)).param("overpayConfirmed", String.valueOf(overpayConfirmed)))
				.andReturn();
	}

	private String page(MockHttpServletRequestBuilder request) throws Exception {
		return mockMvc.perform(request.cookie(a.cookie())).andReturn().getResponse().getContentAsString()
				.replaceAll("\\s+", " ");
	}

	private String status() {
		return jdbc.queryForObject("select status from charge where id = ?", String.class, charge);
	}

}
