package com.rentkeeper.lease;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.LinkedHashMap;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.rentkeeper.TestcontainersConfiguration;
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
 * 청구가 생긴 계약의 제한 (M2 결정 11, M3 결정 5·12·13): 삭제 금지(500이 아니라 안내), 첫 달 금액 정정 금지, 월세
 * 변경 적용월, 종료·나가는 날 단축 뒤 이후 달 청구 자동 취소. 오늘은 2026-10-17(서울).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, MutableClockConfiguration.class})
class LeaseChargeRestrictionTests {

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
		// 2026-01-01 들어옴, 2027-07-01 나감, 받는 날 5일, 첫 달 월세 50만 + 관리비 5만
		LeaseTerms terms = new LeaseTerms(10_000_000, 5, LocalDate.parse("2026-01-01"), LocalDate.parse("2027-07-01"),
				"국민은행", new AccountNumber("1234567890"), new PersonName("김임대"), null);
		lease = leaseService.register(a.landlordId(), new LeaseRegistration(unit, new PersonName("홍길동"),
				PhoneNumber.parse("01011112222"), null, 500000, 50000, terms, false));
	}

	// ---------------------------------------------------------------- 삭제

	@Test
	void leaseWithChargeCannotBeDeletedAndShowsFriendlyMessageInsteadOf500() throws Exception {
		long charge = charge("2026-10-01", "ISSUED", 0);
		jdbc.update("update charge set status = 'CANCELED', canceled_at = now(), cancel_reason = '잘못 만듦' where id = ?",
				charge);

		mockMvc.perform(get("/app/leases/" + lease + "/delete").cookie(a.cookie())).andExpect(status().isOk())
				.andExpect(content().string(containsString("받을 월세가 만들어진 계약은 지울 수 없어요")))
				.andExpect(content().string(not(containsString("네, 지울게요"))));

		mockMvc.perform(post("/app/leases/" + lease + "/delete").with(csrf()).cookie(a.cookie()))
				.andExpect(redirectedUrl("/app/leases/" + lease))
				.andExpect(flash().attribute("notice", containsString("받을 월세가 만들어진 계약은 지울 수 없어요")));
		assertThat(count("lease where id = " + lease)).as("취소한 청구만 있어도 지우지 않는다").isEqualTo(1);
	}

	@Test
	void foreignKeyViolationFromConcurrentBatchIsShownAsFriendlyMessage() throws Exception {
		// 사전 확인(청구 없음)을 지난 뒤 배치가 첫 청구를 넣은 상황: 계약을 지우기 직전에 청구를 넣는 트리거로 재현한다
		jdbc.execute(
				"""
						create or replace function test_insert_charge() returns trigger language plpgsql as $$
						begin
						  insert into charge (landlord_id, lease_id, billing_month, due_date, total_amount, status, source,
						                      issued_at, created_at, updated_at)
						  values (old.landlord_id, old.id, '2026-10-01', '2026-10-05', 550000, 'ISSUED', 'AUTO', now(), now(), now());
						  return old;
						end $$""");
		jdbc.execute("create trigger test_insert_charge before delete on lease for each row "
				+ "execute function test_insert_charge()");
		try {
			mockMvc.perform(post("/app/leases/" + lease + "/delete").with(csrf()).cookie(a.cookie()))
					.andExpect(redirectedUrl("/app/leases/" + lease))
					.andExpect(flash().attribute("notice", containsString("받을 월세가 만들어진 계약은 지울 수 없어요")));
		} finally {
			jdbc.execute("drop trigger test_insert_charge on lease");
			jdbc.execute("drop function test_insert_charge()");
		}
		assertThat(count("lease where id = " + lease)).isEqualTo(1);
		assertThat(count("charge")).as("삭제와 함께 트리거의 청구도 되돌려졌다").isZero();
	}

	@Test
	void leaseWithoutChargeCanStillBeDeleted() throws Exception {
		mockMvc.perform(post("/app/leases/" + lease + "/delete").with(csrf()).cookie(a.cookie()))
				.andExpect(status().is3xxRedirection());
		assertThat(count("lease where id = " + lease)).isZero();
	}

	// ---------------------------------------------------------------- 첫 달 금액

	@Test
	void firstMonthAmountsCannotBeCorrectedOnceFirstMonthIsCharged() throws Exception {
		charge("2026-01-01", "ISSUED", 0);

		Map<String, String> changed = editForm();
		changed.put("firstMonthlyRent", "450000");
		perform("/app/leases/" + lease, changed).andExpect(status().isOk())
				.andExpect(content().string(containsString("첫 달 받을 월세가 이미 만들어져")));
		assertThat(jdbc.queryForObject("select monthly_rent from lease_rent_history where lease_id = ?", Long.class,
				lease)).isEqualTo(500000L);

		Map<String, String> sameAmounts = editForm();
		sameAmounts.put("deposit", "20000000");
		perform("/app/leases/" + lease, sameAmounts).andExpect(redirectedUrl("/app/leases/" + lease));
		assertThat(jdbc.queryForObject("select deposit from lease where id = ?", Long.class, lease))
				.as("첫 달 금액을 그대로 두면 다른 칸은 고칠 수 있다").isEqualTo(20_000_000L);
	}

	// ---------------------------------------------------------------- 월세 변경 적용월

	@Test
	void rentChangeStartsAfterLastChargedMonth() throws Exception {
		// 11월분이 이미 만들어짐 (납부일 며칠 전에 미리 만든 경우)
		charge("2026-11-01", "ISSUED", 0);

		mockMvc.perform(get("/app/leases/" + lease + "/rent-change").cookie(a.cookie()))
				.andExpect(content().string(not(containsString("value=\"2026-11\""))))
				.andExpect(content().string(containsString("value=\"2026-12\"")));
		changeRent("2026-11").andExpect(status().isOk())
				.andExpect(content().string(containsString("받을 월세가 이미 만들어진 달이에요")));
		changeRent("2026-12").andExpect(redirectedUrl("/app/leases/" + lease));
	}

	@Test
	void scheduledChangeForAlreadyChargedMonthCannotBeCanceled() throws Exception {
		changeRent("2026-11").andExpect(redirectedUrl("/app/leases/" + lease));
		long history = jdbc.queryForObject(
				"select id from lease_rent_history where lease_id = ? and effective_from = '2026-11-01'", Long.class,
				lease);
		charge("2026-11-01", "ISSUED", 0);

		mockMvc.perform(get("/app/leases/" + lease + "/rent-history/" + history + "/cancel").cookie(a.cookie()))
				.andExpect(redirectedUrl("/app/leases/" + lease))
				.andExpect(flash().attribute("notice", containsString("받을 월세가 이미 만들어진 달이에요")));
	}

	// ---------------------------------------------------------------- 종료·단축 뒤 이후 달

	@Test
	void endingCancelsLaterUnpaidChargesAndKeepsPaidOnes() throws Exception {
		long october = charge("2026-10-01", "ISSUED", 0);
		long november = charge("2026-11-01", "ISSUED", 0);
		long december = charge("2026-12-01", "PARTIALLY_PAID", 100000);

		// 10/16에 나감 → 마지막 청구월 10월
		mockMvc.perform(
				post("/app/leases/" + lease + "/end").with(csrf()).cookie(a.cookie()).param("endedOn", "2026-10-16"))
				.andExpect(redirectedUrl("/app/leases/" + lease))
				.andExpect(flash().attribute("notice", containsString("이후 달 받을 월세 1건이 취소됐어요")))
				.andExpect(flash().attribute("notice", containsString("입금 기록이 있는 이후 달 받을 월세 1건은 그대로 두었어요")));

		assertThat(chargeStatus(october)).isEqualTo("ISSUED");
		assertThat(chargeStatus(november)).isEqualTo("CANCELED");
		assertThat(chargeStatus(december)).as("입금이 있으면 취소하지 않는다").isEqualTo("PARTIALLY_PAID");
		assertThat(jdbc.queryForMap("select cancel_reason from charge where id = ?", november))
				.containsEntry("cancel_reason", "계약 종료로 자동 취소");
		assertThat(jdbc.queryForMap("select from_status, to_status, actor_type, actor_id, reason "
				+ "from charge_status_history where charge_id = ?", november)).containsEntry("from_status", "ISSUED")
				.containsEntry("to_status", "CANCELED").containsEntry("actor_type", "LANDLORD")
				.containsEntry("actor_id", a.landlordId()).containsEntry("reason", "계약 종료로 자동 취소");
	}

	@Test
	void endingOnMoveInDayCancelsAllUnpaidCharges() throws Exception {
		jdbc.update("update lease set start_date = '2026-10-17', end_date = '2027-10-17' where id = ?", lease);
		jdbc.update("update lease_rent_history set effective_from = '2026-10-01' where lease_id = ?", lease);
		long october = charge("2026-10-01", "ISSUED", 0);

		mockMvc.perform(
				post("/app/leases/" + lease + "/end").with(csrf()).cookie(a.cookie()).param("endedOn", "2026-10-17"))
				.andExpect(flash().attribute("notice", containsString("이후 달 받을 월세 1건이 취소됐어요")));
		assertThat(chargeStatus(october)).as("하루도 살지 않았으면 들어오는 달도 취소").isEqualTo("CANCELED");
	}

	@Test
	void shorteningEndDateCancelsChargesAfterNewLastMonth() throws Exception {
		long december = charge("2026-12-01", "ISSUED", 0);
		long november = charge("2026-11-01", "ISSUED", 0);

		Map<String, String> shorten = editForm();
		shorten.put("endDate", "2026-12-01"); // 마지막 청구월 11월
		perform("/app/leases/" + lease, shorten).andExpect(redirectedUrl("/app/leases/" + lease))
				.andExpect(flash().attribute("notice", containsString("이후 달 받을 월세 1건이 취소됐어요")));

		assertThat(chargeStatus(november)).isEqualTo("ISSUED");
		assertThat(chargeStatus(december)).isEqualTo("CANCELED");
		assertThat(jdbc.queryForObject("select cancel_reason from charge where id = ?", String.class, december))
				.isEqualTo("나가는 날 변경으로 자동 취소");

		Map<String, String> extend = editForm();
		extend.put("endDate", "2027-07-01");
		perform("/app/leases/" + lease, extend).andExpect(flash().attribute("notice", "계약을 고쳤어요."));
		assertThat(chargeStatus(december)).as("다시 늘려도 취소는 되돌리지 않는다").isEqualTo("CANCELED");
	}

	private long charge(String month, String status, long paid) {
		return jdbc.queryForObject("insert into charge (landlord_id, lease_id, billing_month, due_date, total_amount, "
				+ "paid_amount, status, source, issued_at, created_at, updated_at) values (?, ?, cast(? as date), "
				+ "cast(? as date) + 4, 550000, ?, ?, 'AUTO', now(), now(), now()) returning id", Long.class,
				a.landlordId(), lease, month, month, paid, status);
	}

	private String chargeStatus(long chargeId) {
		return jdbc.queryForObject("select status from charge where id = ?", String.class, chargeId);
	}

	private Map<String, String> editForm() {
		Map<String, String> form = new LinkedHashMap<>();
		form.put("tenantName", "홍길동");
		form.put("tenantPhone", "010-1111-2222");
		form.put("tenantMemo", "");
		form.put("deposit", "10000000");
		form.put("firstMonthlyRent", "500000");
		form.put("firstMaintenanceFee", "50000");
		form.put("dueDay", "5");
		form.put("endDate", "2027-07-01");
		form.put("bankName", "국민은행");
		form.put("accountNumber", "1234567890");
		form.put("accountHolder", "김임대");
		form.put("memo", "");
		return form;
	}

	private ResultActions changeRent(String month) throws Exception {
		return mockMvc.perform(post("/app/leases/" + lease + "/rent-change").with(csrf()).cookie(a.cookie())
				.param("effectiveMonth", month).param("monthlyRent", "600000").param("maintenanceFee", "")
				.param("reason", ""));
	}

	private ResultActions perform(String path, Map<String, String> form) throws Exception {
		MockHttpServletRequestBuilder request = post(path).with(csrf()).cookie(a.cookie());
		form.forEach(request::param);
		return mockMvc.perform(request);
	}

	private int count(String fromWhere) {
		return jdbc.queryForObject("select count(*) from " + fromWhere, Integer.class);
	}

}
