package com.rentkeeper.lease;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.rentkeeper.TestcontainersConfiguration;
import com.rentkeeper.landlord.application.LandlordService;
import com.rentkeeper.lease.application.LeaseExceptions.LeaseOverlapException;
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
 * 세입자·계약 등록 (LEASE-1, LEASE-4): 입력 → 확인 → 저장, 암호문 저장, 첫 월세 이력, 호실 상태, 기간 겹침
 * 차단(서비스 + DB, 동시 요청), 같은 연락처 세입자 재사용 확인, 직전 계좌 미리 채우기, 다른 임대인 404, 로그에 평문 없음.
 * 오늘은 2026-10-17(서울).
 */
@SpringBootTest
// 실패 시 요청·응답 덤프(테스트 도구 출력)에 입력값이 찍혀 로그 검사가 흐려지지 않게 끈다. 앱 로그만 검사한다
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import({TestcontainersConfiguration.class, MutableClockConfiguration.class})
@ExtendWith(OutputCaptureExtension.class)
class LeaseRegistrationTests {

	private static final String TENANT_NAME = "홍길동";

	private static final String TENANT_PHONE = "010-9876-5432";

	private static final String ACCOUNT = "110-222-333444";

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

	long unitB;

	@BeforeEach
	void setUp() throws Exception {
		clock.set(MutableClockConfiguration.START);
		TestData.reset(jdbc);
		a = LandlordLogin.signUpAndLogin(mockMvc, landlordService, passwordEncoder, "a@example.com");
		b = LandlordLogin.signUpAndLogin(mockMvc, landlordService, passwordEncoder, "b@example.com");
		long propertyA = property(a.landlordId(), "행복빌라");
		unitA = unit(a.landlordId(), propertyA, "101호");
		unitA2 = unit(a.landlordId(), propertyA, "102호");
		unitB = unit(b.landlordId(), property(b.landlordId(), "남의빌라"), "101호");
	}

	@Test
	void registersLeaseThroughConfirmScreenAndStoresPersonalInfoEncrypted() throws Exception {
		mockMvc.perform(get("/app/leases/new").param("unitId", String.valueOf(unitA)).cookie(a.cookie()))
				.andExpect(status().isOk()).andExpect(content().string(containsString("1. 세입자")))
				.andExpect(content().string(not(containsString("지난번 계약의 계좌"))));
		perform("/app/leases/confirm", form(unitA, "2026-10-01", "2027-10-01")).andExpect(status().isOk())
				.andExpect(content().string(containsString("이대로 등록할까요?")))
				.andExpect(content().string(containsString("500,000원 (50만 원)")))
				.andExpect(content().string(containsString("매달 31일 (31일이 없는 달은 그 달 마지막 날)")));
		assertThat(count("lease")).as("확인 화면에서는 아직 저장하지 않는다").isZero();

		long leaseId = register(a, form(unitA, "2026-10-01", "2027-10-01"));

		Map<String, Object> tenant = jdbc.queryForMap("select name_enc, phone_enc, phone_hash, memo_enc from tenant");
		assertThat(tenant.values()).allSatisfy(value -> assertThat(String.valueOf(value)).doesNotContain(TENANT_NAME)
				.doesNotContain("98765432").doesNotContain("01098765432"));
		assertThat((String) tenant.get("name_enc")).startsWith("v1:");
		assertThat((String) tenant.get("phone_enc")).startsWith("v1:");
		Map<String, Object> lease = jdbc.queryForMap("select * from lease where id = ?", leaseId);
		assertThat((String) lease.get("account_no_enc")).startsWith("v1:").doesNotContain("333444");
		assertThat((String) lease.get("account_holder_enc")).startsWith("v1:").doesNotContain("김임대");
		assertThat(lease).containsEntry("deposit", 10_000_000L).containsEntry("due_day", 31)
				.containsEntry("status", "ACTIVE").containsEntry("bank_name", "국민은행");
		assertThat(jdbc.queryForMap(
				"select monthly_rent, maintenance_fee, effective_from from lease_rent_history " + "where lease_id = ?",
				leaseId)).containsEntry("monthly_rent", 500_000L).containsEntry("maintenance_fee", 50_000L)
				.containsEntry("effective_from", java.sql.Date.valueOf("2026-10-01"));

		// 화면에는 평문으로 보인다 (로그인한 임대인 본인)
		mockMvc.perform(get("/app/leases/" + leaseId).cookie(a.cookie()))
				.andExpect(content().string(containsString(TENANT_NAME)))
				.andExpect(content().string(containsString("010-9876-5432")))
				.andExpect(content().string(containsString("110222333444")))
				.andExpect(content().string(containsString("550,000원")));
		mockMvc.perform(get("/app/units/" + unitA).cookie(a.cookie()))
				.andExpect(content().string(containsString("입주중")))
				.andExpect(content().string(containsString("(현재 계약)")));
		mockMvc.perform(get("/app/properties/" + propertyOf(unitA)).cookie(a.cookie()))
				.andExpect(content().string(containsString("입주중")));
	}

	@Test
	void personalInfoNeverAppearsInLogs(CapturedOutput output) throws Exception {
		register(a, form(unitA, "2026-10-01", "2027-10-01"));
		mockMvc.perform(get("/app/units/" + unitA).cookie(a.cookie()));

		assertThat(output.getAll()).doesNotContain(TENANT_NAME).doesNotContain("01098765432")
				.doesNotContain("110222333444").doesNotContain("세입자 메모 내용");
	}

	@Test
	void nextLeasePrefillsAccountOfSameLandlordOnly() throws Exception {
		register(a, form(unitA, "2026-10-01", "2027-10-01"));

		mockMvc.perform(get("/app/leases/new").param("unitId", String.valueOf(unitA2)).cookie(a.cookie()))
				.andExpect(content().string(containsString("지난번 계약의 계좌를 미리 채웠어요")))
				.andExpect(content().string(containsString("value=\"110222333444\"")));
		mockMvc.perform(get("/app/leases/new").param("unitId", String.valueOf(unitB)).cookie(b.cookie()))
				.andExpect(content().string(not(containsString("110222333444"))));
	}

	@Test
	void validationMessagesAreFriendlyAndDoNotEchoInput() throws Exception {
		Map<String, String> bad = form(unitA, "2026-10-01", "2026-09-01");
		bad.put("tenantPhone", "02-123-4567");
		bad.put("deposit", "천만원");
		bad.put("monthlyRent", "200000000");
		bad.put("dueDay", "32");
		bad.put("accountNumber", "12");

		perform("/app/leases/confirm", bad).andExpect(status().isOk())
				.andExpect(content().string(containsString("휴대폰 번호를 다시 확인해 주세요")))
				.andExpect(content().string(containsString("보증금을 숫자로 입력해 주세요")))
				.andExpect(content().string(containsString("월세는 1억 원 이하로 입력해 주세요")))
				.andExpect(content().string(containsString("매달 받는 날을 1~31일 중에서 골라 주세요")))
				.andExpect(content().string(containsString("나가는 날은 들어오는 날보다 뒤여야 해요")))
				.andExpect(content().string(containsString("계좌번호를 숫자로 입력해 주세요")))
				.andExpect(content().string(not(containsString("Failed to convert"))));
		assertThat(count("lease")).isZero();
	}

	@Test
	void emojiTenantMemoUpTo500CharactersIsAccepted() throws Exception {
		Map<String, String> ok = form(unitA, "2026-10-01", "2027-10-01");
		ok.put("tenantMemo", "😀".repeat(500));
		register(a, ok);

		Map<String, String> tooLong = form(unitA2, "2026-10-01", "2027-10-01");
		tooLong.put("tenantPhone", "010-1111-2222");
		tooLong.put("tenantMemo", "😀".repeat(501));
		perform("/app/leases/confirm", tooLong).andExpect(content().string(containsString("메모는 500자 이하로 입력해 주세요")));
	}

	@Test
	void overlappingActiveLeaseIsRejectedButMoveOutDayIsFree() throws Exception {
		register(a, form(unitA, "2026-01-01", "2026-12-31"));

		Map<String, String> overlap = form(unitA, "2026-12-30", "2027-12-31");
		overlap.put("tenantPhone", "010-1111-2222");
		perform("/app/leases/confirm", overlap).andExpect(content().string(containsString("이 호실에 기간이 겹치는 계약이 있어요")))
				.andExpect(content().string(not(containsString("ex_lease_unit_active_period"))));
		perform("/app/leases", overlap).andExpect(content().string(containsString("이 호실에 기간이 겹치는 계약이 있어요")));

		Map<String, String> touching = form(unitA, "2026-12-31", "2027-12-31");
		touching.put("tenantPhone", "010-1111-2222");
		register(a, touching);
		assertThat(count("lease where unit_id = " + unitA)).isEqualTo(2);
	}

	@Test
	void concurrentOverlappingRegistrationsLeaveOnlyOne() throws Exception {
		int threads = 4;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<String>> results = new ArrayList<>();
		for (int i = 0; i < threads; i++) {
			String phone = "0101234000" + i;
			results.add(pool.submit(() -> {
				start.await();
				try {
					leaseService.register(a.landlordId(), registration(unitA, phone));
					return "ok";
				} catch (LeaseOverlapException e) {
					return "overlap";
				}
			}));
		}
		start.countDown();
		List<String> outcomes = new ArrayList<>();
		for (Future<String> result : results) {
			outcomes.add(result.get());
		}
		pool.shutdown();

		assertThat(outcomes).containsOnlyOnce("ok").containsOnly("ok", "overlap");
		assertThat(count("lease where unit_id = " + unitA + " and status = 'ACTIVE'")).isOne();
	}

	@Test
	void samePhoneAsksToReuseExistingTenant() throws Exception {
		register(a, form(unitA, "2026-10-01", "2027-10-01"));
		Map<String, String> second = form(unitA2, "2026-10-01", "2027-10-01");
		second.put("tenantName", "다른이름");

		perform("/app/leases/confirm", second).andExpect(content().string(containsString("이미 등록된 세입자로 계약할까요?")))
				.andExpect(content().string(containsString(TENANT_NAME)))
				.andExpect(content().string(containsString("네, 이 세입자로 등록할게요")));
		// 확인 없이 저장하려 하면 다시 확인 화면
		perform("/app/leases", second).andExpect(content().string(containsString("이미 등록된 세입자로 계약할까요?")));
		assertThat(count("lease")).isOne();

		second.put("useExistingTenant", "true");
		register(a, second);
		assertThat(count("tenant")).as("같은 번호는 한 사람").isOne();
		assertThat(count("lease")).isEqualTo(2);

		// 다른 임대인은 같은 번호라도 별개의 세입자
		Map<String, String> other = form(unitB, "2026-10-01", "2027-10-01");
		perform(b, "/app/leases/confirm", other).andExpect(content().string(not(containsString("이미 등록된 세입자로 계약할까요?"))));
	}

	@Test
	void otherLandlordsUnitAndLeaseAreNotFound() throws Exception {
		long leaseB = registerAs(b, form(unitB, "2026-10-01", "2027-10-01"));

		mockMvc.perform(get("/app/leases/new").param("unitId", String.valueOf(unitB)).cookie(a.cookie()))
				.andExpect(status().isNotFound());
		perform("/app/leases/confirm", form(unitB, "2026-10-01", "2027-10-01")).andExpect(status().isNotFound());
		perform("/app/leases/edit-draft", form(unitB, "2026-10-01", "2027-10-01")).andExpect(status().isNotFound());
		perform("/app/leases", form(unitB, "2026-10-01", "2027-10-01")).andExpect(status().isNotFound());
		mockMvc.perform(get("/app/leases/" + leaseB).cookie(a.cookie())).andExpect(status().isNotFound())
				.andExpect(content().string(not(containsString(TENANT_NAME))));
		mockMvc.perform(get("/app/units/" + unitB).cookie(a.cookie())).andExpect(status().isNotFound());

		assertThat(count("lease where unit_id = " + unitB)).isOne();
	}

	// ---------------------------------------------------------------- 도우미

	private static Map<String, String> form(long unitId, String start, String end) {
		Map<String, String> form = new LinkedHashMap<>();
		form.put("unitId", String.valueOf(unitId));
		form.put("tenantName", TENANT_NAME);
		form.put("tenantPhone", TENANT_PHONE);
		form.put("tenantMemo", "세입자 메모 내용");
		form.put("deposit", "10,000,000");
		form.put("monthlyRent", "500000");
		form.put("maintenanceFee", "50000");
		form.put("dueDay", "31");
		form.put("startDate", start);
		form.put("endDate", end);
		form.put("bankName", "국민은행");
		form.put("accountNumber", ACCOUNT);
		form.put("accountHolder", "김임대");
		form.put("memo", "");
		return form;
	}

	private static LeaseRegistration registration(long unitId, String phone) {
		LeaseTerms terms = new LeaseTerms(0, 5, LocalDate.of(2026, 10, 1), LocalDate.of(2027, 10, 1), "국민은행",
				new AccountNumber("1234567890"), new PersonName("김임대"), null);
		return new LeaseRegistration(unitId, new PersonName("동시"), PhoneNumber.parse(phone), null, 500000, 0, terms,
				false);
	}

	private ResultActions perform(String path, Map<String, String> form) throws Exception {
		return perform(a, path, form);
	}

	private ResultActions perform(Session session, String path, Map<String, String> form) throws Exception {
		MockHttpServletRequestBuilder request = post(path).with(csrf()).cookie(session.cookie());
		form.forEach(request::param);
		return mockMvc.perform(request);
	}

	private long register(Session session, Map<String, String> form) throws Exception {
		return registerAs(session, form);
	}

	private long registerAs(Session session, Map<String, String> form) throws Exception {
		String location = perform(session, "/app/leases", form).andReturn().getResponse().getRedirectedUrl();
		assertThat(location).as("등록 성공").startsWith("/app/leases/");
		return Long.parseLong(location.substring("/app/leases/".length()));
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

	private long propertyOf(long unitId) {
		return jdbc.queryForObject("select property_id from unit where id = ?", Long.class, unitId);
	}

	private int count(String fromWhere) {
		return jdbc.queryForObject("select count(*) from " + fromWhere, Integer.class);
	}

}
