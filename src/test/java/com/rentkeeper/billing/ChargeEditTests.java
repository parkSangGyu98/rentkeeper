package com.rentkeeper.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.rentkeeper.TestcontainersConfiguration;
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
 * 받을 월세 항목 추가·고치기·지우기와 취소 (M3-b 커밋 3, M3 결정 7·8·12, M3-b 결정 9·10·14), 상세의 바뀐
 * 기록(커밋 4). 10월분 = 월세 500,000 + 관리비 50,000, 납부일 10/25, 오늘 10/17.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, MutableClockConfiguration.class})
class ChargeEditTests {

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
	MutableClock clock;

	Session a;

	long charge;

	long rentItem;

	long feeItem;

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
		long lease = leaseService.register(a.landlordId(), new LeaseRegistration(unit, new PersonName("홍길동"),
				PhoneNumber.parse("01011112222"), null, 500_000, 50_000, terms, false));
		charge = jdbc.queryForObject("insert into charge (landlord_id, lease_id, billing_month, due_date, "
				+ "total_amount, status, source, issued_at, created_at, updated_at) values (?, ?, '2026-10-01', "
				+ "'2026-10-25', 550000, 'ISSUED', 'AUTO', now(), now(), now()) returning id", Long.class,
				a.landlordId(), lease);
		rentItem = item("RENT", "월세", 500_000);
		feeItem = item("MAINTENANCE", "관리비", 50_000);
	}

	@Test
	void addingItemUsesTypeNameWhenLabelIsBlankAndRecordsHistory() throws Exception {
		MvcResult result = add("ELECTRIC", "", "30,000", version());

		assertThat(result.getFlashMap().get("notice")).isEqualTo("항목을 추가했어요. 받을 돈은 580,000원, 지금 상태는 '받을 차례'예요.");
		assertThat(jdbc.queryForMap("select type, label, amount from charge_item where type = 'ELECTRIC'"))
				.containsEntry("label", "전기").containsEntry("amount", 30_000L);
		assertThat(total()).isEqualTo(580_000L);
		assertThat(lastItemHistory()).containsEntry("action", "ADD").containsEntry("after_amount", 30_000L)
				.containsEntry("actor_type", "LANDLORD").containsEntry("actor_id", a.landlordId());
	}

	@Test
	void changingAmountRecordsBeforeAndAfter() throws Exception {
		edit(rentItem, "월세 (10/16 입주, 일할)", "250,000", version());

		assertThat(total()).isEqualTo(300_000L);
		assertThat(lastItemHistory()).containsEntry("action", "CHANGE").containsEntry("before_amount", 500_000L)
				.containsEntry("after_amount", 250_000L).containsEntry("label", "월세 (10/16 입주, 일할)")
				.containsEntry("actor_type", "LANDLORD");
	}

	@Test
	void totalChangesMoveStatusBetweenPaidAndPartiallyPaid() throws Exception {
		paymentService.record(a.landlordId(), charge, version(),
				new PaymentInput(550_000, LocalDate.parse("2026-10-17"), null, false));
		assertThat(status()).isEqualTo("PAID");

		add("OTHER", "수리비", "10,000", version());
		assertThat(status()).isEqualTo("PARTIALLY_PAID");
		assertThat(jdbc.queryForObject(
				"select reason from charge_status_history where charge_id = ? order by id desc " + "limit 1",
				String.class, charge)).isEqualTo("항목 수정");

		long repair = jdbc.queryForObject("select id from charge_item where label = '수리비'", Long.class);
		delete(repair, version());
		assertThat(status()).isEqualTo("PAID");
		assertThat(lastItemHistory()).containsEntry("action", "REMOVE").containsEntry("before_amount", 10_000L)
				.containsEntry("after_amount", null);
	}

	@Test
	void zeroTotalAndLastItemAreBlockedWithCancelGuide() throws Exception {
		edit(rentItem, "월세", "0", version());
		assertThat(total()).isEqualTo(50_000L);

		assertThat(body(edit(feeItem, "관리비", "0", version())))
				.contains("받을 금액이 0원이 돼요. 이 달 월세를 받지 않으려면 [받을 월세 취소]를 써 주세요.");
		assertThat(delete(feeItem, version()).getFlashMap().get("chargeAlert"))
				.isEqualTo("받을 금액이 0원이 돼요. 이 달 월세를 받지 않으려면 [받을 월세 취소]를 써 주세요.");

		delete(rentItem, version());
		assertThat(delete(feeItem, version()).getFlashMap().get("chargeAlert"))
				.isEqualTo("마지막 항목은 지울 수 없어요. 이 달 월세를 받지 않으려면 [받을 월세 취소]를 써 주세요.");
		assertThat(total()).isEqualTo(50_000L);
	}

	@Test
	void lastItemDeleteRequestIsBlockedEvenWithoutConfirmPage() throws Exception {
		delete(rentItem, version());
		long seen = version();
		long historyBefore = jdbc.queryForObject("select count(*) from charge_item_history where charge_id = ?",
				Long.class, charge);

		// 화면 링크 없이 요청만 보내도(올바른 version) 서버가 막는다
		assertThat(delete(feeItem, seen).getFlashMap().get("chargeAlert"))
				.isEqualTo("마지막 항목은 지울 수 없어요. 이 달 월세를 받지 않으려면 [받을 월세 취소]를 써 주세요.");

		assertThat(jdbc.queryForObject("select count(*) from charge_item where charge_id = ?", Long.class, charge))
				.isEqualTo(1L);
		assertThat(
				jdbc.queryForObject("select count(*) from charge_item_history where charge_id = ?", Long.class, charge))
				.isEqualTo(historyBefore);
		assertThat(total()).isEqualTo(50_000L);
		assertThat(version()).isEqualTo(seen);
	}

	@Test
	void deletingTwoLastItemsAtTheSameMomentLeavesOne() throws Exception {
		// 두 기기(서로 다른 로그인 세션)에서 남은 두 항목을 하나씩 동시에 지운다
		jakarta.servlet.http.Cookie other = mockMvc.perform(
				post("/login").with(csrf()).param("email", "a@example.com").param("password", LandlordLogin.PASSWORD))
				.andReturn().getResponse().getCookie("SESSION");
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			for (int round = 0; round < 5; round++) {
				long first = round == 0 ? rentItem : remainingItem();
				long second = round == 0 ? feeItem : item("OTHER", "기타", 10_000);
				long seen = version();
				CountDownLatch start = new CountDownLatch(1);
				List<Future<MvcResult>> results = new ArrayList<>();
				for (long target : List.of(first, second)) {
					jakarta.servlet.http.Cookie session = target == first ? a.cookie() : other;
					results.add(executor.submit(() -> {
						start.await();
						return mockMvc.perform(post("/app/charges/" + charge + "/items/" + target + "/delete")
								.with(csrf()).cookie(session).param("version", String.valueOf(seen))).andReturn();
					}));
				}
				start.countDown();
				for (Future<MvcResult> result : results) {
					result.get(30, TimeUnit.SECONDS);
				}

				assertThat(
						jdbc.queryForObject("select count(*) from charge_item where charge_id = ?", Long.class, charge))
						.as("%d번째: 항목이 하나는 남는다", round + 1).isEqualTo(1L);
				assertThat(total()).isPositive();
			}
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void itemInputGuides() throws Exception {
		assertThat(body(add("ELECTRIC", "", "삼만", version()))).contains("금액을 숫자로 입력해 주세요 (예: 30,000)");
		assertThat(body(add("ELECTRIC", "", "-1", version()))).contains("금액을 숫자로 입력해 주세요 (예: 30,000)");
		assertThat(body(add("ELECTRIC", "", "100,000,001", version()))).contains("항목 금액은 1억 원까지예요");
		assertThat(body(add("ELECTRIC", "가".repeat(51), "1000", version()))).contains("이름은 50자 이하로 입력해 주세요");
		assertThat(body(add("NOPE", "", "1000", version()))).contains("항목 종류를 골라 주세요");
		assertThat(jdbc.queryForObject("select count(*) from charge_item", Integer.class)).isEqualTo(2);
	}

	@Test
	void cancelShowsGuideRequiresReasonAndIsFinal() throws Exception {
		assertThat(page("/app/charges/" + charge + "/cancel")).contains("이 달은 새로 만들 수 없어요. 금액이 틀렸다면 취소 대신 항목을 고치세요.");

		assertThat(body(cancel(version(), " "))).contains("취소 사유를 1~200자로 입력해 주세요 (예: 잘못 만듦)");
		assertThat(status()).isEqualTo("ISSUED");

		MvcResult result = cancel(version(), "이 달은 받지 않기로 함");
		assertThat(result.getFlashMap().get("notice")).isEqualTo("받을 월세를 취소했어요. 이 달은 새로 만들 수 없어요.");
		assertThat(jdbc.queryForMap("select status, cancel_reason from charge where id = ?", charge))
				.containsEntry("status", "CANCELED").containsEntry("cancel_reason", "이 달은 받지 않기로 함");
		assertThat(
				jdbc.queryForMap("select to_status, actor_type, reason from charge_status_history where charge_id = ? "
						+ "order by id desc limit 1", charge))
				.containsEntry("to_status", "CANCELED").containsEntry("actor_type", "LANDLORD")
				.containsEntry("reason", "이 달은 받지 않기로 함");

		String detail = page("/app/charges/" + charge);
		assertThat(detail).contains("취소함").contains("취소 사유: <span>이 달은 받지 않기로 함</span>").doesNotContain("다 받았어요")
				.doesNotContain("항목 추가").doesNotContain("받을 월세 취소");
		assertThat(add("OTHER", "", "1000", version()).getFlashMap().get("chargeAlert"))
				.isEqualTo("취소한 받을 월세는 고칠 수 없어요.");
	}

	@Test
	void chargeWithPaymentsCannotBeCanceled() throws Exception {
		paymentService.record(a.landlordId(), charge, version(),
				new PaymentInput(100_000, LocalDate.parse("2026-10-17"), null, false));

		assertThat(page("/app/charges/" + charge)).doesNotContain("받을 월세 취소");
		assertThat(page("/app/charges/" + charge + "/cancel")).contains("입금 기록이 있어 취소할 수 없어요. 입금 기록을 먼저 지워 주세요.");
		assertThat(cancel(version(), "사유").getFlashMap().get("chargeAlert"))
				.isEqualTo("입금 기록이 있어 취소할 수 없어요. 입금 기록을 먼저 지워 주세요.");
		assertThat(status()).isEqualTo("PARTIALLY_PAID");
	}

	@Test
	void detailShowsStatusAndItemHistoryInSeoulTime() throws Exception {
		// 배치가 10/1 03:00(서울) = 9/30 18:00(UTC)에 만들고 발행한 기록
		statusHistory(null, "SCHEDULED", "자동 생성", "2026-09-30T18:00:00Z");
		statusHistory("SCHEDULED", "ISSUED", "자동 발행 — 알림 발송은 M5", "2026-09-30T18:00:00Z");
		jdbc.update(
				"insert into charge_item_history (landlord_id, charge_id, charge_item_id, action, item_type, label, "
						+ "after_amount, actor_type, created_at) values (?, ?, ?, 'ADD', 'RENT', '월세', 500000, 'SYSTEM', "
						+ "cast('2026-09-30T18:00:00Z' as timestamptz))",
				a.landlordId(), charge, rentItem);
		// 임대인이 10/17 09:00(서울)에 고치고 취소
		edit(rentItem, "월세", "250,000", version());
		cancel(version(), "잘못 만듦 — 다음 달에 다시 확인");

		String detail = page("/app/charges/" + charge);
		assertThat(detail).contains("상태 기록 <span>3</span>건").contains("항목 기록 <span>2</span>건")
				.contains("<strong>준비 중</strong>").contains("<strong>준비 중 → 받을 차례</strong>")
				.contains("<span>2026년 10월 1일 03:00</span> · <span>자동</span><span> · 자동 발행</span>")
				.contains("<strong>받을 차례 → 취소함</strong>")
				.contains("<span>2026년 10월 17일 09:00</span> · <span>내가 바꿈</span><span> · 잘못 만듦 — 다음 달에 다시 확인</span>")
				.contains("<strong>추가: 월세 500,000원</strong>").contains("<strong>고침: 월세 500,000원 → 250,000원</strong>");
		assertThat(detail).as("개발용 메모는 화면에서만 뗀다").doesNotContain("알림 발송은 M5");
		assertThat(jdbc.queryForObject("select count(*) from charge_status_history where reason = ?", Integer.class,
				"자동 발행 — 알림 발송은 M5")).as("DB 기록은 그대로").isEqualTo(1);
	}

	@Test
	void staleVersionAndOtherChargesItem() throws Exception {
		long stale = version();
		add("OTHER", "", "1000", stale);
		assertThat(add("OTHER", "", "1000", stale).getFlashMap().get("chargeAlert")).asString()
				.startsWith("이 월세 내용이 방금 바뀌었어요.");

		long other = jdbc.queryForObject("insert into charge (landlord_id, lease_id, billing_month, due_date, "
				+ "total_amount, status, source, issued_at, created_at, updated_at) select landlord_id, lease_id, "
				+ "'2026-09-01', '2026-09-25', 1000, 'ISSUED', 'AUTO', now(), now(), now() from charge where id = ? "
				+ "returning id", Long.class, charge);
		long otherItem = jdbc.queryForObject(
				"insert into charge_item (landlord_id, charge_id, type, label, amount, "
						+ "created_at, updated_at) values (?, ?, 'RENT', '월세', 1000, now(), now()) returning id",
				Long.class, a.landlordId(), other);
		assertThat(mockMvc.perform(get("/app/charges/" + charge + "/items/" + otherItem + "/edit").cookie(a.cookie()))
				.andReturn().getResponse().getStatus()).isEqualTo(404);
		assertThat(edit(otherItem, "탈취", "1", version()).getResponse().getStatus()).isEqualTo(404);
	}

	private long item(String type, String label, long amount) {
		return jdbc.queryForObject(
				"insert into charge_item (landlord_id, charge_id, type, label, amount, created_at, "
						+ "updated_at) values (?, ?, ?, ?, ?, now(), now()) returning id",
				Long.class, a.landlordId(), charge, type, label, amount);
	}

	private void statusHistory(String from, String to, String reason, String at) {
		jdbc.update(
				"insert into charge_status_history (landlord_id, charge_id, from_status, to_status, actor_type, "
						+ "reason, created_at) values (?, ?, ?, ?, 'SYSTEM', ?, cast(? as timestamptz))",
				a.landlordId(), charge, from, to, reason, at);
	}

	private MvcResult add(String type, String label, String amount, long seenVersion) throws Exception {
		return mockMvc.perform(post("/app/charges/" + charge + "/items").with(csrf()).cookie(a.cookie())
				.param("type", type).param("label", label).param("amount", amount).param("memo", "")
				.param("version", String.valueOf(seenVersion))).andReturn();
	}

	private MvcResult edit(long itemId, String label, String amount, long seenVersion) throws Exception {
		return mockMvc.perform(post("/app/charges/" + charge + "/items/" + itemId).with(csrf()).cookie(a.cookie())
				.param("type", "RENT").param("label", label).param("amount", amount).param("memo", "")
				.param("version", String.valueOf(seenVersion))).andReturn();
	}

	private MvcResult delete(long itemId, long seenVersion) throws Exception {
		return mockMvc.perform(post("/app/charges/" + charge + "/items/" + itemId + "/delete").with(csrf())
				.cookie(a.cookie()).param("version", String.valueOf(seenVersion))).andReturn();
	}

	private MvcResult cancel(long seenVersion, String reason) throws Exception {
		return mockMvc.perform(post("/app/charges/" + charge + "/cancel").with(csrf()).cookie(a.cookie())
				.param("version", String.valueOf(seenVersion)).param("reason", reason)).andReturn();
	}

	private String page(String path) throws Exception {
		return mockMvc.perform(get(path).cookie(a.cookie())).andReturn().getResponse().getContentAsString()
				.replaceAll("\\s+", " ");
	}

	private static String body(MvcResult result) throws Exception {
		return result.getResponse().getContentAsString();
	}

	private long version() {
		return jdbc.queryForObject("select version from charge where id = ?", Long.class, charge);
	}

	private long remainingItem() {
		return jdbc.queryForObject("select id from charge_item where charge_id = ?", Long.class, charge);
	}

	private long total() {
		return jdbc.queryForObject("select total_amount from charge where id = ?", Long.class, charge);
	}

	private String status() {
		return jdbc.queryForObject("select status from charge where id = ?", String.class, charge);
	}

	private Map<String, Object> lastItemHistory() {
		return jdbc.queryForMap("select action, label, before_amount, after_amount, actor_type, actor_id "
				+ "from charge_item_history where charge_id = ? order by id desc limit 1", charge);
	}

}
