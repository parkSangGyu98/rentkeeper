package com.rentkeeper.lease;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
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
import com.rentkeeper.billing.application.ChargeGenerator;
import com.rentkeeper.landlord.application.LandlordService;
import com.rentkeeper.support.LandlordLogin;
import com.rentkeeper.support.LandlordLogin.Session;
import com.rentkeeper.support.MutableClock;
import com.rentkeeper.support.MutableClockConfiguration;
import com.rentkeeper.support.TestData;

/**
 * 계약의 청구 설정 (M3 결정 2·17): "납부일 며칠 전에 받을 월세를 만들까요?" 칸(등록·수정, 0~28)과 "이번 달 월세는
 * 자동으로 만들어지지 않아요" 안내(등록 확인·계약 상세). 오늘은 2026-10-17(서울).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, MutableClockConfiguration.class})
class LeaseBillingSettingsTests {

	private static final String NOT_AUTOMATIC = "이번 달 월세는 자동으로 만들어지지 않아요";

	private static final String SEVEN_SELECTED = "<option value=\"7\" selected=\"selected\">납부일 7일 전</option>";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	LandlordService landlordService;

	@Autowired
	PasswordEncoder passwordEncoder;

	@Autowired
	ChargeGenerator generator;

	@Autowired
	MutableClock clock;

	Session a;

	long unit;

	@BeforeEach
	void setUp() throws Exception {
		clock.set(MutableClockConfiguration.START);
		TestData.reset(jdbc);
		a = LandlordLogin.signUpAndLogin(mockMvc, landlordService, passwordEncoder, "a@example.com");
		long property = jdbc.queryForObject("insert into property (landlord_id, name, created_at, updated_at) "
				+ "values (?, '행복빌라', now(), now()) returning id", Long.class, a.landlordId());
		unit = jdbc.queryForObject(
				"insert into unit (landlord_id, property_id, name, type, created_at, updated_at) "
						+ "values (?, ?, '101호', 'ONE_ROOM', now(), now()) returning id",
				Long.class, a.landlordId(), property);
	}

	@Test
	void registrationFormDefaultsToSevenDaysAndSavesChosenValue() throws Exception {
		assertThat(page(get("/app/leases/new").param("unitId", String.valueOf(unit))))
				.contains("납부일 며칠 전에 받을 월세를 만들까요?").contains(SEVEN_SELECTED);

		Map<String, String> form = form("25", "28");
		perform("/app/leases/confirm", form).andExpect(content().string(containsString("납부일 28일 전")))
				.andExpect(content().string(containsString("name=\"issueDaysBefore\" value=\"28\"")));
		perform("/app/leases", form).andExpect(redirectedUrlPattern("/app/leases/*"));

		assertThat(jdbc.queryForObject("select issue_days_before from lease", Integer.class)).isEqualTo(28);
	}

	@Test
	void outOfRangeValueIsRejectedWithGuide() throws Exception {
		perform("/app/leases/confirm", form("25", "29")).andExpect(status().isOk())
				.andExpect(content().string(containsString("받을 월세를 만들 날을 납부일 당일 ~ 28일 전 중에서 골라 주세요")));
		perform("/app/leases/confirm", form("25", "-1"))
				.andExpect(content().string(containsString("받을 월세를 만들 날을 납부일 당일 ~ 28일 전 중에서 골라 주세요")));
	}

	@Test
	void editChangesValueAndBatchUsesIt() throws Exception {
		perform("/app/leases", form("5", "7")).andExpect(redirectedUrlPattern("/app/leases/*"));
		long lease = jdbc.queryForObject("select id from lease", Long.class);
		assertThat(page(get("/app/leases/" + lease + "/edit"))).contains(SEVEN_SELECTED);

		Map<String, String> edit = editForm();
		edit.put("issueDaysBefore", "0");
		perform("/app/leases/" + lease, edit).andExpect(redirectedUrlPattern("/app/leases/*"));
		assertThat(jdbc.queryForObject("select issue_days_before from lease", Integer.class)).isZero();
		mockMvc.perform(get("/app/leases/" + lease).cookie(a.cookie()))
				.andExpect(content().string(containsString("납부일 당일")));

		// 11월분(납부일 11/5)은 당일에 만든다
		clock.set(java.time.Instant.parse("2026-11-03T18:00:00Z")); // 서울 11/4 03:00
		generator.run();
		assertThat(jdbc.queryForList("select billing_month from charge", LocalDate.class))
				.doesNotContain(LocalDate.parse("2026-11-01"));
		clock.set(java.time.Instant.parse("2026-11-04T18:00:00Z")); // 서울 11/5 03:00
		generator.run();
		assertThat(jdbc.queryForList("select billing_month from charge", LocalDate.class))
				.contains(LocalDate.parse("2026-11-01"));
	}

	@Test
	void confirmScreenWarnsWhenThisMonthsDueDateHasPassed() throws Exception {
		perform("/app/leases/confirm", form("5", "7")).andExpect(content().string(containsString(NOT_AUTOMATIC)));
		// 납부일이 오늘이면 자동으로 만든다
		perform("/app/leases/confirm", form("17", "7")).andExpect(content().string(not(containsString(NOT_AUTOMATIC))));
		// 다음 달에 들어오면 이번 달은 기간 밖
		perform("/app/leases/confirm", formWithPeriod("5", "2026-11-01", "2027-11-01"))
				.andExpect(content().string(not(containsString(NOT_AUTOMATIC))));
	}

	@Test
	void detailWarnsUntilThisMonthsChargeExists() throws Exception {
		perform("/app/leases", form("5", "7")).andExpect(redirectedUrlPattern("/app/leases/*"));
		long lease = jdbc.queryForObject("select id from lease", Long.class);

		generator.run();
		assertThat(jdbc.queryForObject("select count(*) from charge", Integer.class)).as("10월분은 자동으로 만들지 않았다").isZero();
		mockMvc.perform(get("/app/leases/" + lease).cookie(a.cookie()))
				.andExpect(content().string(containsString(NOT_AUTOMATIC)));

		// 임대인이 이번 달분을 직접 만든 뒤에는 안내하지 않는다 (직접 만들기 화면은 M3-b)
		jdbc.update("insert into charge (landlord_id, lease_id, billing_month, due_date, total_amount, status, source, "
				+ "issued_at, created_at, updated_at) values (?, ?, '2026-10-01', '2026-10-05', 550000, 'ISSUED', "
				+ "'MANUAL', now(), now(), now())", a.landlordId(), lease);
		mockMvc.perform(get("/app/leases/" + lease).cookie(a.cookie()))
				.andExpect(content().string(not(containsString(NOT_AUTOMATIC))));
	}

	private Map<String, String> form(String dueDay, String issueDaysBefore) {
		Map<String, String> form = formWithPeriod(dueDay, "2026-10-01", "2027-10-01");
		form.put("issueDaysBefore", issueDaysBefore);
		return form;
	}

	private Map<String, String> formWithPeriod(String dueDay, String start, String end) {
		Map<String, String> form = new LinkedHashMap<>();
		form.put("unitId", String.valueOf(unit));
		form.put("tenantName", "홍길동");
		form.put("tenantPhone", "010-1111-2222");
		form.put("tenantMemo", "");
		form.put("deposit", "10000000");
		form.put("monthlyRent", "500000");
		form.put("maintenanceFee", "50000");
		form.put("dueDay", dueDay);
		form.put("issueDaysBefore", "7");
		form.put("startDate", start);
		form.put("endDate", end);
		form.put("bankName", "국민은행");
		form.put("accountNumber", "1234567890");
		form.put("accountHolder", "김임대");
		form.put("memo", "");
		return form;
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
		form.put("issueDaysBefore", "7");
		form.put("endDate", "2027-10-01");
		form.put("bankName", "국민은행");
		form.put("accountNumber", "1234567890");
		form.put("accountHolder", "김임대");
		form.put("memo", "");
		return form;
	}

	/** 화면 HTML (줄바꿈·들여쓰기를 공백 하나로 줄여 비교한다) */
	private String page(MockHttpServletRequestBuilder request) throws Exception {
		return mockMvc.perform(request.cookie(a.cookie())).andReturn().getResponse().getContentAsString()
				.replaceAll("\\s+", " ");
	}

	private ResultActions perform(String path, Map<String, String> form) throws Exception {
		MockHttpServletRequestBuilder request = post(path).with(csrf()).cookie(a.cookie());
		form.forEach(request::param);
		return mockMvc.perform(request);
	}

}
