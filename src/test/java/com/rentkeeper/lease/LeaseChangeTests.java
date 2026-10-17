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

import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
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
 * 계약 수정·월세 변경·미래 변경 취소·계약 종료·삭제 (LEASE-2·3, M2 결정 3·4·11·12). 오늘은
 * 2026-10-17(서울), 이번 달은 2026년 10월. 종료일 "오늘까지" 검사의 서울 자정 경계와 다른 임대인 404도 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, MutableClockConfiguration.class})
class LeaseChangeTests {

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

	Session b;

	long unitA;

	long unitA2;

	long leaseA;

	@BeforeEach
	void setUp() throws Exception {
		clock.set(MutableClockConfiguration.START);
		TestData.reset(jdbc);
		a = LandlordLogin.signUpAndLogin(mockMvc, landlordService, passwordEncoder, "a@example.com");
		b = LandlordLogin.signUpAndLogin(mockMvc, landlordService, passwordEncoder, "b@example.com");
		long property = property(a.landlordId(), "행복빌라");
		unitA = unit(a.landlordId(), property, "101호");
		unitA2 = unit(a.landlordId(), property, "102호");
		// 2026-01-01 들어옴, 2027-07-01 나감 (마지막 달 2027년 6월)
		leaseA = leaseService.register(a.landlordId(), registration(unitA, "01011112222", "2026-01-01", "2027-07-01"));
	}

	// ---------------------------------------------------------------- 수정

	@Test
	void editsTermsTenantAndFirstMonthAmounts() throws Exception {
		mockMvc.perform(get("/app/leases/" + leaseA + "/edit").cookie(a.cookie()))
				.andExpect(content().string(containsString("value=\"홍길동\"")))
				.andExpect(content().string(containsString("호실과 들어오는 날은 바꿀 수 없어요")));

		Map<String, String> form = editForm();
		form.put("tenantName", "홍길순");
		form.put("deposit", "20,000,000");
		form.put("firstMonthlyRent", "450000");
		form.put("endDate", "2027-12-31");
		form.put("accountNumber", "999-888-777666");
		form.put("startDate", "2020-01-01");
		form.put("unitId", String.valueOf(unitA2));
		perform(a, "/app/leases/" + leaseA, form).andExpect(redirectedUrl("/app/leases/" + leaseA));

		assertThat(jdbc.queryForMap("select unit_id, start_date, end_date, deposit from lease where id = ?", leaseA))
				.as("호실·들어오는 날은 요청에 넣어도 바뀌지 않는다").containsEntry("unit_id", unitA)
				.containsEntry("start_date", Date.valueOf("2026-01-01"))
				.containsEntry("end_date", Date.valueOf("2027-12-31")).containsEntry("deposit", 20_000_000L);
		assertThat(jdbc.queryForObject("select monthly_rent from lease_rent_history where lease_id = ?", Long.class,
				leaseA)).isEqualTo(450000L);
		mockMvc.perform(get("/app/leases/" + leaseA).cookie(a.cookie()))
				.andExpect(content().string(containsString("홍길순")))
				.andExpect(content().string(containsString("999888777666")));
	}

	@Test
	void editRechecksOverlapScheduledChangesAndPhone() throws Exception {
		leaseService.register(a.landlordId(), registration(unitA, "01033334444", "2027-07-01", "2028-07-01"));
		leaseService.register(a.landlordId(), registration(unitA2, "01055556666", "2026-01-01", "2027-01-01"));

		Map<String, String> extend = editForm();
		extend.put("endDate", "2027-08-01");
		perform(a, "/app/leases/" + leaseA, extend)
				.andExpect(content().string(containsString("같은 호실의 다른 계약과 기간이 겹쳐요")));

		changeRent("2027-03", "600000").andExpect(redirectedUrl("/app/leases/" + leaseA));
		Map<String, String> shorten = editForm();
		shorten.put("endDate", "2027-02-01");
		perform(a, "/app/leases/" + leaseA, shorten)
				.andExpect(content().string(containsString("새 나가는 날 뒤로 잡힌 월세 변경이 있어요")));

		Map<String, String> phone = editForm();
		phone.put("tenantPhone", "010-5555-6666");
		perform(a, "/app/leases/" + leaseA, phone)
				.andExpect(content().string(containsString("이 휴대폰 번호는 다른 세입자가 이미 쓰고 있어요")));

		assertThat(jdbc.queryForObject("select end_date from lease where id = ?", Date.class, leaseA))
				.isEqualTo(Date.valueOf("2027-07-01"));
	}

	// ---------------------------------------------------------------- 월세 변경

	@Test
	void rentChangeOffersMonthsFromThisMonthToLastMonthAndReplacesFutureChange() throws Exception {
		mockMvc.perform(get("/app/leases/" + leaseA + "/rent-change").cookie(a.cookie()))
				.andExpect(content().string(containsString("value=\"2026-10\"")))
				.andExpect(content().string(containsString("value=\"2027-06\"")))
				.andExpect(content().string(not(containsString("value=\"2026-09\""))))
				.andExpect(content().string(not(containsString("value=\"2027-07\""))));

		changeRent("2027-01", "550000").andExpect(redirectedUrl("/app/leases/" + leaseA))
				.andExpect(flash().attribute("notice", "2027년 1월부터 월세를 바꿨어요."));
		changeRent("2027-01", "570000").andExpect(redirectedUrl("/app/leases/" + leaseA));

		assertThat(rentRows()).as("같은 미래 달은 교체: 행은 하나").containsExactly(
				Map.of("effective_from", Date.valueOf("2026-01-01"), "monthly_rent", 500000L),
				Map.of("effective_from", Date.valueOf("2027-01-01"), "monthly_rent", 570000L));
		mockMvc.perform(get("/app/leases/" + leaseA).cookie(a.cookie()))
				.andExpect(content().string(containsString("앞으로 바뀌는 금액")))
				.andExpect(content().string(containsString("2027년 1월부터 월세 570,000원")))
				.andExpect(content().string(containsString("500,000원")));
	}

	@Test
	void rentChangeRejectsStartedPastFirstAndOutOfLeaseMonths() throws Exception {
		changeRent("2026-10", "520000").andExpect(redirectedUrl("/app/leases/" + leaseA));

		changeRent("2026-10", "530000").andExpect(content().string(containsString("이미 적용 중인 달의 금액은 바꿀 수 없어요")));
		changeRent("2026-09", "530000").andExpect(content().string(containsString("지난 달부터는 바꿀 수 없어요")));
		changeRent("2027-07", "530000").andExpect(content().string(containsString("계약 기간 안의 달을 골라 주세요")));
		changeRent("bad", "530000").andExpect(content().string(containsString("적용할 달을 골라 주세요")));

		long futureLease = leaseService.register(a.landlordId(),
				registration(unitA2, "01077778888", "2026-12-01", "2027-12-01"));
		mockMvc.perform(post("/app/leases/" + futureLease + "/rent-change").with(csrf()).cookie(a.cookie())
				.param("effectiveMonth", "2026-12").param("monthlyRent", "1"))
				.andExpect(content().string(containsString("첫 달 금액은 [계약 고치기]에서 고쳐 주세요")));
		assertThat(jdbc.queryForObject(
				"select monthly_rent from lease_rent_history where lease_id = ? and " + "effective_from = '2026-10-01'",
				Long.class, leaseA)).isEqualTo(520000L);
	}

	@Test
	void futureChangeCanBeCancelledButStartedChangeCannot() throws Exception {
		changeRent("2026-10", "520000");
		changeRent("2027-02", "600000");
		long started = historyId("2026-10-01");
		long future = historyId("2027-02-01");

		mockMvc.perform(get("/app/leases/" + leaseA + "/rent-history/" + future + "/cancel").cookie(a.cookie()))
				.andExpect(content().string(containsString("예정된 월세 변경을 취소할까요?")));
		mockMvc.perform(
				post("/app/leases/" + leaseA + "/rent-history/" + future + "/cancel").with(csrf()).cookie(a.cookie()))
				.andExpect(redirectedUrl("/app/leases/" + leaseA))
				.andExpect(flash().attribute("notice", "예정된 월세 변경을 취소했어요."));
		mockMvc.perform(
				post("/app/leases/" + leaseA + "/rent-history/" + started + "/cancel").with(csrf()).cookie(a.cookie()))
				.andExpect(flash().attribute("notice", "이미 적용 중인 달의 금액은 바꿀 수 없어요. 다음 달 이후로 골라 주세요"));
		mockMvc.perform(post("/app/leases/" + leaseA + "/rent-history/" + historyId("2026-01-01") + "/cancel")
				.with(csrf()).cookie(a.cookie())).andExpect(flash().attribute("notice", "첫 달 금액은 [계약 고치기]에서 고쳐 주세요"));

		assertThat(rentRows()).hasSize(2);
	}

	// ---------------------------------------------------------------- 종료

	@Test
	void endingLeaseFreesUnitAndMakesLeaseReadOnly() throws Exception {
		mockMvc.perform(get("/app/leases/" + leaseA + "/end").cookie(a.cookie()))
				.andExpect(content().string(containsString("계약을 종료할까요?")))
				.andExpect(content().string(containsString("value=\"2026-10-17\"")));
		mockMvc.perform(get("/app/units/" + unitA).cookie(a.cookie()))
				.andExpect(content().string(containsString("입주중")));

		mockMvc.perform(
				post("/app/leases/" + leaseA + "/end").with(csrf()).cookie(a.cookie()).param("endedOn", "2026-10-15"))
				.andExpect(redirectedUrl("/app/leases/" + leaseA));

		assertThat(jdbc.queryForMap("select status, ended_on from lease where id = ?", leaseA))
				.containsEntry("status", "ENDED").containsEntry("ended_on", Date.valueOf("2026-10-15"));
		mockMvc.perform(get("/app/units/" + unitA).cookie(a.cookie())).andExpect(content().string(containsString("공실")))
				.andExpect(content().string(containsString("(종료)")));
		mockMvc.perform(get("/app/leases/" + leaseA).cookie(a.cookie()))
				.andExpect(content().string(containsString("종료한 계약은 고칠 수 없어요")))
				.andExpect(content().string(not(containsString("월세 바꾸기"))));

		// 종료한 계약은 고칠 수 없다: 화면과 POST 모두 상세로 돌려보내고 바뀌지 않는다
		for (String path : List.of("/app/leases/" + leaseA + "/edit", "/app/leases/" + leaseA + "/rent-change",
				"/app/leases/" + leaseA + "/end")) {
			mockMvc.perform(get(path).cookie(a.cookie())).andExpect(redirectedUrl("/app/leases/" + leaseA))
					.andExpect(flash().attribute("notice", "종료한 계약은 고칠 수 없어요."));
		}
		perform(a, "/app/leases/" + leaseA, editForm()).andExpect(redirectedUrl("/app/leases/" + leaseA));
		mockMvc.perform(
				post("/app/leases/" + leaseA + "/end").with(csrf()).cookie(a.cookie()).param("endedOn", "2026-10-16"))
				.andExpect(redirectedUrl("/app/leases/" + leaseA));
		mockMvc.perform(post("/app/leases/" + leaseA + "/rent-change").with(csrf()).cookie(a.cookie())
				.param("effectiveMonth", "2027-01").param("monthlyRent", "1"))
				.andExpect(redirectedUrl("/app/leases/" + leaseA));
		assertThat(jdbc.queryForObject("select ended_on from lease where id = ?", Date.class, leaseA))
				.isEqualTo(Date.valueOf("2026-10-15"));
		assertThat(rentRows()).hasSize(1);

		// 같은 호실에 새 계약을 바로 넣을 수 있다
		leaseService.register(a.landlordId(), registration(unitA, "01099990000", "2026-10-15", "2027-10-15"));
	}

	@Test
	void endedOnTodayFollowsSeoulMidnight() throws Exception {
		long lease = leaseService.register(a.landlordId(),
				registration(unitA2, "01012121212", "2026-10-01", "2027-10-01"));

		// 서울 2026-10-17 23:59:59 (UTC 14:59:59): 오늘은 17일 → 18일은 미래라 거부
		clock.set(Instant.parse("2026-10-17T14:59:59Z"));
		mockMvc.perform(
				post("/app/leases/" + lease + "/end").with(csrf()).cookie(a.cookie()).param("endedOn", "2026-10-18"))
				.andExpect(status().isOk()).andExpect(content().string(containsString("들어오는 날부터 오늘까지 중에서 골라 주세요")));
		assertThat(jdbc.queryForObject("select status from lease where id = ?", String.class, lease))
				.isEqualTo("ACTIVE");

		// 서울 2026-10-18 00:00:00 (UTC 15:00:00): 오늘은 18일 → 허용
		clock.set(Instant.parse("2026-10-17T15:00:00Z"));
		mockMvc.perform(
				post("/app/leases/" + lease + "/end").with(csrf()).cookie(a.cookie()).param("endedOn", "2026-10-18"))
				.andExpect(redirectedUrl("/app/leases/" + lease));
		assertThat(jdbc.queryForObject("select ended_on from lease where id = ?", LocalDate.class, lease))
				.isEqualTo(LocalDate.of(2026, 10, 18));
	}

	@Test
	void endedOnBeforeStartIsRejected() throws Exception {
		mockMvc.perform(
				post("/app/leases/" + leaseA + "/end").with(csrf()).cookie(a.cookie()).param("endedOn", "2025-12-31"))
				.andExpect(content().string(containsString("들어오는 날부터 오늘까지 중에서 골라 주세요")));
		mockMvc.perform(post("/app/leases/" + leaseA + "/end").with(csrf()).cookie(a.cookie()).param("endedOn", ""))
				.andExpect(content().string(containsString("들어오는 날부터 오늘까지 중에서 골라 주세요")));
	}

	// ---------------------------------------------------------------- 삭제

	@Test
	void deletingLeaseRemovesHistoryAndReturnsToUnit() throws Exception {
		changeRent("2027-01", "550000");
		mockMvc.perform(get("/app/leases/" + leaseA + "/delete").cookie(a.cookie()))
				.andExpect(content().string(containsString("계약을 지울까요?")));

		mockMvc.perform(post("/app/leases/" + leaseA + "/delete").with(csrf()).cookie(a.cookie()))
				.andExpect(redirectedUrl("/app/units/" + unitA));

		assertThat(count("lease where id = " + leaseA)).isZero();
		assertThat(count("lease_rent_history where lease_id = " + leaseA)).isZero();
		mockMvc.perform(get("/app/units/" + unitA).cookie(a.cookie()))
				.andExpect(content().string(containsString("공실")));
	}

	// ---------------------------------------------------------------- 다른 임대인

	@Test
	void otherLandlordsLeaseChangesAreNotFoundAndChangeNothing() throws Exception {
		changeRent("2027-01", "550000");
		long future = historyId("2027-01-01");
		Map<String, Object> before = jdbc.queryForMap("select * from lease where id = ?", leaseA);

		for (String path : List.of("/edit", "/rent-change", "/end", "/delete", "/rent-history/" + future + "/cancel")) {
			mockMvc.perform(get("/app/leases/" + leaseA + path).cookie(b.cookie())).andExpect(status().isNotFound());
		}
		perform(b, "/app/leases/" + leaseA, editForm()).andExpect(status().isNotFound());
		mockMvc.perform(post("/app/leases/" + leaseA + "/rent-change").with(csrf()).cookie(b.cookie())
				.param("effectiveMonth", "2027-02").param("monthlyRent", "1")).andExpect(status().isNotFound());
		mockMvc.perform(
				post("/app/leases/" + leaseA + "/rent-history/" + future + "/cancel").with(csrf()).cookie(b.cookie()))
				.andExpect(status().isNotFound());
		mockMvc.perform(
				post("/app/leases/" + leaseA + "/end").with(csrf()).cookie(b.cookie()).param("endedOn", "2026-10-17"))
				.andExpect(status().isNotFound());
		mockMvc.perform(post("/app/leases/" + leaseA + "/delete").with(csrf()).cookie(b.cookie()))
				.andExpect(status().isNotFound());

		assertThat(jdbc.queryForMap("select * from lease where id = ?", leaseA)).isEqualTo(before);
		assertThat(rentRows()).hasSize(2);
	}

	@Test
	void historyIdOfAnotherLeaseIsNotFound() throws Exception {
		long other = leaseService.register(a.landlordId(),
				registration(unitA2, "01044445555", "2026-01-01", "2027-07-01"));
		mockMvc.perform(post("/app/leases/" + other + "/rent-change").with(csrf()).cookie(a.cookie())
				.param("effectiveMonth", "2027-01").param("monthlyRent", "1"));
		long otherFuture = jdbc.queryForObject(
				"select id from lease_rent_history where lease_id = ? and " + "effective_from = '2027-01-01'",
				Long.class, other);

		mockMvc.perform(post("/app/leases/" + leaseA + "/rent-history/" + otherFuture + "/cancel").with(csrf())
				.cookie(a.cookie())).andExpect(status().isNotFound());
		assertThat(count("lease_rent_history where id = " + otherFuture)).isOne();
	}

	// ---------------------------------------------------------------- 도우미

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

	private ResultActions changeRent(String month, String rent) throws Exception {
		return mockMvc.perform(post("/app/leases/" + leaseA + "/rent-change").with(csrf()).cookie(a.cookie())
				.param("effectiveMonth", month).param("monthlyRent", rent).param("maintenanceFee", "")
				.param("reason", ""));
	}

	private ResultActions perform(Session session, String path, Map<String, String> form) throws Exception {
		MockHttpServletRequestBuilder request = post(path).with(csrf()).cookie(session.cookie());
		form.forEach(request::param);
		return mockMvc.perform(request);
	}

	private static LeaseRegistration registration(long unitId, String phone, String start, String end) {
		LeaseTerms terms = new LeaseTerms(10_000_000, 5, LocalDate.parse(start), LocalDate.parse(end), "국민은행",
				new AccountNumber("1234567890"), new PersonName("김임대"), null);
		return new LeaseRegistration(unitId, new PersonName("홍길동"), PhoneNumber.parse(phone), null, 500000, 50000,
				terms, false);
	}

	private List<Map<String, Object>> rentRows() {
		return jdbc.queryForList("select effective_from, monthly_rent from lease_rent_history where lease_id = ? "
				+ "order by effective_from", leaseA);
	}

	private long historyId(String effectiveFrom) {
		return jdbc.queryForObject(
				"select id from lease_rent_history where lease_id = ? and effective_from = " + "cast(? as date)",
				Long.class, leaseA, effectiveFrom);
	}

	private long property(long landlordId, String name) {
		return jdbc
				.queryForObject("insert into property (landlord_id, name, created_at, updated_at) values (?, ?, now(), "
						+ "now()) returning id", Long.class, landlordId, name);
	}

	private long unit(long landlordId, long propertyId, String name) {
		return jdbc.queryForObject(
				"insert into unit (landlord_id, property_id, name, type, created_at, updated_at) "
						+ "values (?, ?, ?, 'ONE_ROOM', now(), now()) returning id",
				Long.class, landlordId, propertyId, name);
	}

	private int count(String fromWhere) {
		return jdbc.queryForObject("select count(*) from " + fromWhere, Integer.class);
	}

}
