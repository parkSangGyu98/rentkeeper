package com.rentkeeper.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

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
 * 입금 정정·삭제 (M3-b 커밋 2, M3 결정 11): 삭제 뒤 상태 재계산(다 받음 → 일부 받음·받을 차례·납부일 지남), 정정 감사
 * 기록(원래 행 보존, replaces_id, 상태 이력), 사유 필수, 이미 지운 기록, 같은 금액 정정의 version. 오늘은
 * 2026-10-17, 10월분 550,000원, 납부일 10/25.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, MutableClockConfiguration.class})
class PaymentCorrectionTests {

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
		charge = charge("2026-10-01", "2026-10-25");
	}

	@Test
	void deletingFromPaidBeforeDueDateGoesBackToPartiallyPaid() throws Exception {
		pay(charge, 200_000);
		long second = pay(charge, 350_000);
		assertThat(status()).isEqualTo("PAID");

		MvcResult result = delete(second, version(), "금액을 잘못 입력함");
		assertThat(result.getFlashMap().get("notice")).isEqualTo("입금 기록을 지웠어요. 지금 상태는 '일부 받음'이에요.");

		assertThat(jdbc.queryForMap("select status, paid_amount, paid_at from charge where id = ?", charge))
				.containsEntry("status", "PARTIALLY_PAID").containsEntry("paid_amount", 200_000L)
				.containsEntry("paid_at", null);
		assertThat(jdbc.queryForMap(
				"select deleted_at is not null as deleted, delete_reason from payment_record " + "where id = ?",
				second)).containsEntry("deleted", true).containsEntry("delete_reason", "금액을 잘못 입력함");
		assertThat(lastHistory()).containsEntry("from_status", "PAID").containsEntry("to_status", "PARTIALLY_PAID")
				.containsEntry("actor_type", "LANDLORD").containsEntry("reason", "입금 기록 삭제");
	}

	@Test
	void deletingOnlyPaymentGoesBackToIssued() throws Exception {
		long only = pay(charge, 550_000);
		delete(only, version(), "다른 세입자 입금을 잘못 기록함");

		assertThat(status()).isEqualTo("ISSUED");
		assertThat(lastHistory()).containsEntry("from_status", "PAID").containsEntry("to_status", "ISSUED");
	}

	@Test
	void deletingFromPaidAfterDueDateBecomesOverdue() throws Exception {
		long only = pay(charge, 550_000);
		clock.set(Instant.parse("2026-10-25T15:30:00Z")); // 서울 10/26, 납부일 다음 날

		MvcResult result = delete(only, version(), "잘못 기록함");

		assertThat(result.getFlashMap().get("notice")).isEqualTo("입금 기록을 지웠어요. 지금 상태는 '납부일 지남'이에요.");
		assertThat(status()).isEqualTo("OVERDUE");
		assertThat(lastHistory()).containsEntry("from_status", "PAID").containsEntry("to_status", "OVERDUE");
	}

	@Test
	void correctionKeepsOriginalAsDeletedAndLinksNewRecord() throws Exception {
		long original = pay(charge, 500_000);
		assertThat(status()).isEqualTo("PARTIALLY_PAID");

		MvcResult result = correct(original, version(), "550,000", "2026-10-16", "금액을 잘못 입력함", false);
		assertThat(result.getFlashMap().get("notice")).isEqualTo("입금 기록을 고쳤어요. 지금 상태는 '다 받음'이에요.");

		assertThat(jdbc.queryForMap("select amount, deleted_at is not null as deleted, delete_reason, replaces_id "
				+ "from payment_record where id = ?", original)).containsEntry("amount", 500_000L)
				.containsEntry("deleted", true).containsEntry("delete_reason", "금액을 잘못 입력함")
				.containsEntry("replaces_id", null);
		assertThat(jdbc.queryForMap("select amount, paid_on, deleted_at, replaces_id from payment_record "
				+ "where charge_id = ? and id <> ?", charge, original)).containsEntry("amount", 550_000L)
				.containsEntry("paid_on", java.sql.Date.valueOf("2026-10-16")).containsEntry("deleted_at", null)
				.containsEntry("replaces_id", original);
		assertThat(status()).isEqualTo("PAID");
		assertThat(lastHistory()).containsEntry("from_status", "PARTIALLY_PAID").containsEntry("to_status", "PAID")
				.containsEntry("reason", "입금 정정");

		String detail = page("/app/charges/" + charge);
		assertThat(detail).contains("지운 입금 기록 <span>1</span>건").contains("(사유: 금액을 잘못 입력함)").contains("고친 기록이에요.");
	}

	@Test
	void correctingWithSameTotalStillBumpsVersionByExactlyOne() throws Exception {
		long original = pay(charge, 550_000);
		long before = version();
		int histories = count("charge_status_history");

		correct(original, before, "550000", "2026-10-15", "받은 날을 잘못 입력함", false);

		assertThat(version()).isEqualTo(before + 1);
		assertThat(status()).isEqualTo("PAID");
		assertThat(count("charge_status_history")).as("상태가 그대로면 이력을 남기지 않는다").isEqualTo(histories);
		// 같은 금액 정정도 그 사이 다른 창의 작업을 막는다
		MvcResult stale = delete(original + 1, before, "다시");
		assertThat(stale.getFlashMap().get("chargeAlert")).asString().contains("이 월세 내용이 방금 바뀌었어요");
	}

	@Test
	void correctionCanBeCorrectedAgain() throws Exception {
		long original = pay(charge, 100_000);
		correct(original, version(), "200000", "2026-10-17", "1차", false);
		long second = jdbc.queryForObject("select id from payment_record where replaces_id = ?", Long.class, original);

		correct(second, version(), "300000", "2026-10-17", "2차", false);

		assertThat(jdbc.queryForObject("select count(*) from payment_record where deleted_at is null", Integer.class))
				.isEqualTo(1);
		assertThat(jdbc.queryForObject("select paid_amount from charge where id = ?", Long.class, charge))
				.isEqualTo(300_000L);
	}

	@Test
	void reasonIsRequiredForDeleteAndCorrect() throws Exception {
		long only = pay(charge, 550_000);
		long before = version();

		assertThat(body(delete(only, before, "  "))).contains("사유를 1~200자로 입력해 주세요 (예: 금액을 잘못 입력함)");
		assertThat(body(correct(only, before, "500000", "2026-10-17", "", false)))
				.contains("사유를 1~200자로 입력해 주세요 (예: 금액을 잘못 입력함)");
		assertThat(body(delete(only, before, "가".repeat(201)))).contains("사유를 1~200자로 입력해 주세요");

		assertThat(version()).as("아무것도 바뀌지 않았다").isEqualTo(before);
		assertThat(status()).isEqualTo("PAID");
	}

	@Test
	void correctionInputGuidesAndOverpaymentConfirmation() throws Exception {
		long only = pay(charge, 500_000);
		long before = version();

		assertThat(body(correct(only, before, "0", "2026-10-17", "사유", false)))
				.contains("받은 금액을 1원 이상 입력해 주세요 (예: 500,000)");
		assertThat(body(correct(only, before, "500000", "2026-10-18", "사유", false)))
				.contains("받은 날은 오늘이나 그 전 날짜로 골라 주세요");
		assertThat(body(correct(only, before, "600,000", "2026-10-17", "사유", false)))
				.contains("남은 금액보다 50,000원 많아요. 이대로 기록할까요?");

		correct(only, before, "600,000", "2026-10-17", "사유", true);
		assertThat(jdbc.queryForObject("select paid_amount from charge where id = ?", Long.class, charge))
				.isEqualTo(600_000L);
	}

	@Test
	void deletedRecordCannotBeDeletedOrCorrectedAgain() throws Exception {
		long only = pay(charge, 550_000);
		delete(only, version(), "잘못 기록함");

		assertThat(delete(only, version(), "또").getFlashMap().get("chargeAlert"))
				.isEqualTo("이미 지운 입금 기록이에요. 아래 최신 내용을 확인해 주세요.");
		assertThat(mockMvc.perform(get("/app/charges/" + charge + "/payments/" + only + "/correct").cookie(a.cookie()))
				.andReturn().getFlashMap().get("chargeAlert")).isEqualTo("이미 지운 입금 기록이에요. 아래 최신 내용을 확인해 주세요.");
	}

	@Test
	void staleVersionOnDeleteShowsChangedGuide() throws Exception {
		long first = pay(charge, 100_000);
		long stale = version();
		pay(charge, 100_000);

		MvcResult result = delete(first, stale, "잘못 기록함");
		assertThat(result.getFlashMap().get("chargeAlert")).asString().startsWith("이 월세 내용이 방금 바뀌었어요.");
		assertThat(
				jdbc.queryForObject("select count(*) from payment_record where deleted_at is not null", Integer.class))
				.isZero();
	}

	@Test
	void paymentOfAnotherChargeIsNotFoundUnderThisCharge() throws Exception {
		long other = charge("2026-09-01", "2026-09-25");
		long otherPayment = pay(other, 100_000);

		assertThat(mockMvc
				.perform(get("/app/charges/" + charge + "/payments/" + otherPayment + "/delete").cookie(a.cookie()))
				.andReturn().getResponse().getStatus()).isEqualTo(404);
		assertThat(delete(otherPayment, version(), "섞기").getResponse().getStatus()).isEqualTo(404);
		assertThat(
				jdbc.queryForObject("select deleted_at from payment_record where id = ?", Instant.class, otherPayment))
				.isNull();
	}

	private long charge(String month, String due) {
		return jdbc.queryForObject("insert into charge (landlord_id, lease_id, billing_month, due_date, total_amount, "
				+ "status, source, issued_at, created_at, updated_at) values (?, ?, cast(? as date), cast(? as date), "
				+ "550000, 'ISSUED', 'AUTO', now(), now(), now()) returning id", Long.class, a.landlordId(), lease,
				month, due);
	}

	/** 서비스로 입금하고 그 기록 ID */
	private long pay(long chargeId, long amount) {
		long seen = jdbc.queryForObject("select version from charge where id = ?", Long.class, chargeId);
		paymentService.record(a.landlordId(), chargeId, seen,
				new PaymentInput(amount, LocalDate.parse("2026-10-17"), null, true));
		return jdbc.queryForObject("select max(id) from payment_record where charge_id = ?", Long.class, chargeId);
	}

	private MvcResult delete(long paymentId, long seenVersion, String reason) throws Exception {
		return mockMvc
				.perform(post("/app/charges/" + charge + "/payments/" + paymentId + "/delete").with(csrf())
						.cookie(a.cookie()).param("version", String.valueOf(seenVersion)).param("reason", reason))
				.andReturn();
	}

	private MvcResult correct(long paymentId, long seenVersion, String amount, String paidOn, String reason,
			boolean overpayConfirmed) throws Exception {
		return mockMvc.perform(post("/app/charges/" + charge + "/payments/" + paymentId + "/correct").with(csrf())
				.cookie(a.cookie()).param("version", String.valueOf(seenVersion)).param("amount", amount)
				.param("paidOn", paidOn).param("memo", "").param("reason", reason)
				.param("overpayConfirmed", String.valueOf(overpayConfirmed))).andReturn();
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

	private String status() {
		return jdbc.queryForObject("select status from charge where id = ?", String.class, charge);
	}

	private Map<String, Object> lastHistory() {
		return jdbc.queryForMap("select from_status, to_status, actor_type, reason from charge_status_history "
				+ "where charge_id = ? order by id desc limit 1", charge);
	}

	private int count(String table) {
		return jdbc.queryForObject("select count(*) from " + table, Integer.class);
	}

}
