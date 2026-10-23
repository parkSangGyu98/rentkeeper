package com.rentkeeper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.rentkeeper.landlord.application.LandlordService;
import com.rentkeeper.lease.application.LeaseChangeService;
import com.rentkeeper.lease.application.LeaseService;
import com.rentkeeper.lease.application.LeaseViews.LeaseRegistration;
import com.rentkeeper.lease.domain.AccountNumber;
import com.rentkeeper.lease.domain.LeaseTerms;
import com.rentkeeper.lease.domain.PersonName;
import com.rentkeeper.lease.domain.PhoneNumber;
import com.rentkeeper.property.application.PropertyService;
import com.rentkeeper.property.application.PropertyViews.PropertyInput;
import com.rentkeeper.property.application.PropertyViews.UnitInput;
import com.rentkeeper.property.domain.UnitType;
import com.rentkeeper.support.LandlordLogin;
import com.rentkeeper.support.LandlordLogin.Session;
import com.rentkeeper.support.MutableClock;
import com.rentkeeper.support.MutableClockConfiguration;
import com.rentkeeper.support.TestData;

/**
 * 임대인 간 데이터 접근(IDOR) 전수 테스트 (PRD 12장 필수 7, ADR-018).
 * <p>
 * 임대인 B가 임대인 A의 건물·호실·계약·월세 이력 ID로 <b>모든</b> 소유 데이터 엔드포인트를 호출한다. 결과는 전부 404여야
 * 하고, 응답 본문은 없는 ID의 404와 같아야 하며(존재 여부 비노출), 소유 데이터는 한 글자도 바뀌지 않아야 한다.
 * <p>
 * 새 엔드포인트를 만들고 이 표에 넣지 않으면 {@link #everyOwnedEndpointIsInTheIdorTable()}이 실패한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, MutableClockConfiguration.class})
class IdorTests {

	/** 소유 데이터 엔드포인트의 경로 앞부분 */
	private static final List<String> OWNED_PREFIXES = List.of("/app/properties", "/app/units", "/app/leases",
			"/app/charges");

	/**
	 * 남의 ID를 받지 않는 엔드포인트 (목록·새 화면·자기 건물 만들기). 여기에 넣을 때는 이유가 분명해야 한다. 받을 월세 목록은 달만
	 * 받고 로그인한 임대인 것만 보인다(chargeListShowsOnlyOwnCharges).
	 */
	private static final Set<String> NO_FOREIGN_ID = Set.of("GET /app/properties", "GET /app/properties/new",
			"POST /app/properties", "GET /app/charges");

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	LandlordService landlordService;

	@Autowired
	PasswordEncoder passwordEncoder;

	@Autowired
	PropertyService propertyService;

	@Autowired
	LeaseService leaseService;

	@Autowired
	LeaseChangeService leaseChangeService;

	@Autowired
	MutableClock clock;

	@Autowired
	@Qualifier("requestMappingHandlerMapping")
	RequestMappingHandlerMapping handlerMapping;

	Session attacker;

	Map<String, String> victimIds;

	@BeforeEach
	void setUp() throws Exception {
		clock.set(MutableClockConfiguration.START);
		TestData.reset(jdbc);
		Session victim = LandlordLogin.signUpAndLogin(mockMvc, landlordService, passwordEncoder, "victim@example.com");
		attacker = LandlordLogin.signUpAndLogin(mockMvc, landlordService, passwordEncoder, "attacker@example.com");

		long property = propertyService.create(victim.landlordId(), new PropertyInput("피해자빌라", "서울", "메모"));
		long unit = propertyService.addUnit(victim.landlordId(), property,
				new UnitInput("101호", UnitType.ONE_ROOM, null));
		LeaseTerms terms = new LeaseTerms(10_000_000, 5, LocalDate.of(2026, 1, 1), LocalDate.of(2027, 7, 1), "국민은행",
				new AccountNumber("1234567890"), new PersonName("김임대"), null);
		long lease = leaseService.register(victim.landlordId(), new LeaseRegistration(unit, new PersonName("피해자세입자"),
				PhoneNumber.parse("01011112222"), null, 500000, 0, terms, false));
		leaseChangeService.changeRent(victim.landlordId(), lease, YearMonth.of(2027, 1), 550000, 0, null);
		long history = jdbc.queryForObject(
				"select id from lease_rent_history where lease_id = ? and effective_from = '2027-01-01'", Long.class,
				lease);
		// 피해자의 10월 받을 월세
		long charge = jdbc.queryForObject("insert into charge (landlord_id, lease_id, billing_month, due_date, "
				+ "total_amount, status, source, issued_at, created_at, updated_at) values (?, ?, '2026-10-01', "
				+ "'2026-10-05', 500000, 'ISSUED', 'AUTO', now(), now(), now()) returning id", Long.class,
				victim.landlordId(), lease);
		long payment = jdbc.queryForObject(
				"insert into payment_record (landlord_id, charge_id, amount, paid_on, source, "
						+ "created_at, updated_at) values (?, ?, 100000, '2026-10-05', 'MANUAL', now(), now()) returning id",
				Long.class, victim.landlordId(), charge);
		victimIds = Map.of("id", "", "property", String.valueOf(property), "unit", String.valueOf(unit), "lease",
				String.valueOf(lease), "history", String.valueOf(history), "charge", String.valueOf(charge), "payment",
				String.valueOf(payment));
	}

	/** (메서드 경로) → 공격자가 보낼 경로와 폼. {property}·{unit}·{lease}·{history}는 피해자의 ID */
	private static Map<String, Attack> attacks() {
		Map<String, Attack> attacks = new LinkedHashMap<>();
		// 건물·호실 (property 모듈)
		attacks.put("GET /app/properties/{id}", Attack.get("/app/properties/{property}"));
		attacks.put("GET /app/properties/{id}/edit", Attack.get("/app/properties/{property}/edit"));
		attacks.put("POST /app/properties/{id}", Attack.post("/app/properties/{property}", "name", "탈취"));
		attacks.put("GET /app/properties/{id}/delete", Attack.get("/app/properties/{property}/delete"));
		attacks.put("POST /app/properties/{id}/delete", Attack.post("/app/properties/{property}/delete"));
		attacks.put("POST /app/properties/{propertyId}/units",
				Attack.post("/app/properties/{property}/units", "name", "999호", "type", "ONE_ROOM"));
		attacks.put("GET /app/units/{id}/edit", Attack.get("/app/units/{unit}/edit"));
		attacks.put("POST /app/units/{id}", Attack.post("/app/units/{unit}", "name", "탈취", "type", "OTHER"));
		attacks.put("GET /app/units/{id}/delete", Attack.get("/app/units/{unit}/delete"));
		attacks.put("POST /app/units/{id}/delete", Attack.post("/app/units/{unit}/delete"));
		// 호실 화면·계약 (lease 모듈). 계약 등록은 호실 ID를 폼으로 받는다
		attacks.put("GET /app/units/{id}", Attack.get("/app/units/{unit}"));
		attacks.put("GET /app/leases/new", Attack.get("/app/leases/new", "unitId", "{unit}"));
		attacks.put("POST /app/leases/confirm", Attack.post("/app/leases/confirm", leaseForm()));
		attacks.put("POST /app/leases/edit-draft", Attack.post("/app/leases/edit-draft", leaseForm()));
		attacks.put("POST /app/leases", Attack.post("/app/leases", leaseForm()));
		attacks.put("GET /app/leases/{id}", Attack.get("/app/leases/{lease}"));
		attacks.put("GET /app/leases/{id}/edit", Attack.get("/app/leases/{lease}/edit"));
		attacks.put("POST /app/leases/{id}", Attack.post("/app/leases/{lease}", editForm()));
		attacks.put("GET /app/leases/{id}/rent-change", Attack.get("/app/leases/{lease}/rent-change"));
		attacks.put("POST /app/leases/{id}/rent-change",
				Attack.post("/app/leases/{lease}/rent-change", "effectiveMonth", "2027-02", "monthlyRent", "1"));
		attacks.put("GET /app/leases/{id}/rent-history/{historyId}/cancel",
				Attack.get("/app/leases/{lease}/rent-history/{history}/cancel"));
		attacks.put("POST /app/leases/{id}/rent-history/{historyId}/cancel",
				Attack.post("/app/leases/{lease}/rent-history/{history}/cancel"));
		attacks.put("GET /app/leases/{id}/end", Attack.get("/app/leases/{lease}/end"));
		attacks.put("POST /app/leases/{id}/end", Attack.post("/app/leases/{lease}/end", "endedOn", "2026-10-17"));
		attacks.put("GET /app/leases/{id}/delete", Attack.get("/app/leases/{lease}/delete"));
		attacks.put("POST /app/leases/{id}/delete", Attack.post("/app/leases/{lease}/delete"));
		// 받을 월세 (billing 모듈)
		attacks.put("GET /app/charges/{id}", Attack.get("/app/charges/{charge}"));
		attacks.put("GET /app/charges/{id}/payments/new",
				Attack.get("/app/charges/{charge}/payments/new", "full", "true"));
		attacks.put("POST /app/charges/{id}/payments", Attack.post("/app/charges/{charge}/payments", "amount", "1",
				"paidOn", "2026-10-17", "version", "0", "full", "false", "overpayConfirmed", "true"));
		attacks.put("GET /app/charges/{id}/payments/{paymentId}/delete",
				Attack.get("/app/charges/{charge}/payments/{payment}/delete"));
		attacks.put("POST /app/charges/{id}/payments/{paymentId}/delete",
				Attack.post("/app/charges/{charge}/payments/{payment}/delete", "version", "0", "reason", "탈취"));
		attacks.put("GET /app/charges/{id}/payments/{paymentId}/correct",
				Attack.get("/app/charges/{charge}/payments/{payment}/correct"));
		attacks.put("POST /app/charges/{id}/payments/{paymentId}/correct",
				Attack.post("/app/charges/{charge}/payments/{payment}/correct", "version", "0", "amount", "1", "paidOn",
						"2026-10-17", "reason", "탈취", "overpayConfirmed", "true"));
		return attacks;
	}

	@Test
	void everyOwnedEndpointWithVictimsIdIsNotFoundAndChangesNothing() throws Exception {
		String before = snapshot();
		String notFound = notFoundBody();
		List<String> failures = new ArrayList<>();

		for (Map.Entry<String, Attack> entry : attacks().entrySet()) {
			MvcResult result = mockMvc.perform(entry.getValue().build(victimIds, attacker)).andReturn();
			int status = result.getResponse().getStatus();
			String body = result.getResponse().getContentAsString();
			if (status != 404) {
				failures.add(entry.getKey() + " → " + status + " (404여야 함)");
			} else if (!body.equals(notFound)) {
				failures.add(entry.getKey() + " → 404 본문이 없는 ID의 404와 다름 (존재 여부 노출)");
			}
			if (body.contains("피해자")) {
				failures.add(entry.getKey() + " → 피해자 데이터가 응답에 보임");
			}
		}

		assertThat(failures).isEmpty();
		assertThat(snapshot()).as("피해자 데이터가 하나도 바뀌지 않음").isEqualTo(before);
	}

	@Test
	void ownChargeWithVictimsPaymentIdIsNotFound() throws Exception {
		long property = propertyService.create(attacker.landlordId(), new PropertyInput("공격자빌라", "서울", null));
		long unit = propertyService.addUnit(attacker.landlordId(), property,
				new UnitInput("201호", UnitType.ONE_ROOM, null));
		LeaseTerms terms = new LeaseTerms(0, 5, LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1), "국민은행",
				new AccountNumber("1234567890"), new PersonName("공격자"), null);
		long lease = leaseService.register(attacker.landlordId(), new LeaseRegistration(unit, new PersonName("공격자세입자"),
				PhoneNumber.parse("01099998888"), null, 300000, 0, terms, false));
		long own = jdbc.queryForObject("insert into charge (landlord_id, lease_id, billing_month, due_date, "
				+ "total_amount, status, source, issued_at, created_at, updated_at) values (?, ?, '2026-10-01', "
				+ "'2026-10-05', 300000, 'ISSUED', 'AUTO', now(), now(), now()) returning id", Long.class,
				attacker.landlordId(), lease);
		String before = snapshot();

		Map<String, String> ids = new java.util.HashMap<>(victimIds);
		ids.put("own", String.valueOf(own));
		for (Attack attack : List.of(Attack.get("/app/charges/{own}/payments/{payment}/delete"),
				Attack.get("/app/charges/{own}/payments/{payment}/correct"),
				Attack.post("/app/charges/{own}/payments/{payment}/delete", "version", "0", "reason", "섞기"),
				Attack.post("/app/charges/{own}/payments/{payment}/correct", "version", "0", "amount", "1", "paidOn",
						"2026-10-17", "reason", "섞기", "overpayConfirmed", "true"))) {
			assertThat(mockMvc.perform(attack.build(ids, attacker)).andReturn().getResponse().getStatus())
					.as(attack.path()).isEqualTo(404);
		}
		assertThat(snapshot()).as("자기 청구에 남의 입금 ID를 섞어도 아무것도 바뀌지 않는다").isEqualTo(before);
	}

	@Test
	void chargeListShowsOnlyOwnCharges() throws Exception {
		String body = mockMvc.perform(Attack.get("/app/charges", "month", "2026-10").build(victimIds, attacker))
				.andReturn().getResponse().getContentAsString();
		assertThat(body).contains("이 달에 받을 월세가 없어요").doesNotContain("피해자세입자").doesNotContain("피해자빌라");
	}

	@Test
	void everyOwnedEndpointIsInTheIdorTable() {
		Set<String> mapped = new TreeSet<>();
		handlerMapping.getHandlerMethods().keySet().forEach(info -> info.getPatternValues().forEach(pattern -> {
			if (OWNED_PREFIXES.stream().anyMatch(pattern::startsWith)) {
				info.getMethodsCondition().getMethods().forEach(method -> mapped.add(method.name() + " " + pattern));
			}
		}));
		assertThat(mapped).as("소유 데이터 엔드포인트가 있어야 한다").isNotEmpty();

		Set<String> covered = new TreeSet<>(attacks().keySet());
		covered.addAll(NO_FOREIGN_ID);
		assertThat(covered).as("새 엔드포인트는 attacks() 표에 넣어 IDOR를 시험한다").containsAll(mapped);
		assertThat(mapped).as("표에 없어진 엔드포인트가 남아 있지 않다").containsAll(covered);
	}

	// ---------------------------------------------------------------- 도우미

	/** 소유 데이터 전체를 한 문자열로 (바뀌었는지 비교용) */
	private String snapshot() {
		StringBuilder all = new StringBuilder();
		for (String table : List.of("property", "unit", "tenant", "lease", "lease_rent_history", "charge",
				"charge_item", "payment_record", "charge_status_history", "charge_item_history")) {
			all.append(table).append(':').append(jdbc.queryForObject(
					"select coalesce(string_agg(t::text, '|' order by t.id), '') from " + table + " t", String.class));
		}
		return all.toString();
	}

	private String notFoundBody() throws Exception {
		return mockMvc.perform(Attack.get("/app/properties/999999999").build(victimIds, attacker)).andReturn()
				.getResponse().getContentAsString();
	}

	private static String[] leaseForm() {
		return new String[]{"unitId", "{unit}", "tenantName", "공격자", "tenantPhone", "010-9999-8888", "deposit", "0",
				"monthlyRent", "1", "dueDay", "1", "startDate", "2030-01-01", "endDate", "2031-01-01", "bankName", "은행",
				"accountNumber", "1234567890", "accountHolder", "공격자", "useExistingTenant", "true"};
	}

	private static String[] editForm() {
		return new String[]{"tenantName", "탈취", "tenantPhone", "010-9999-8888", "deposit", "0", "firstMonthlyRent", "1",
				"dueDay", "1", "endDate", "2031-01-01", "bankName", "은행", "accountNumber", "1234567890",
				"accountHolder", "탈취"};
	}

	private record Attack(HttpMethod method, String path, String[] params) {

		static Attack get(String path, String... params) {
			return new Attack(HttpMethod.GET, path, params);
		}

		static Attack post(String path, String... params) {
			return new Attack(HttpMethod.POST, path, params);
		}

		MockHttpServletRequestBuilder build(Map<String, String> ids, Session session) {
			MockHttpServletRequestBuilder builder = request(method, fill(path, ids)).cookie(session.cookie());
			if (method == HttpMethod.POST) {
				builder.with(csrf());
			}
			for (int i = 0; i + 1 < params.length; i += 2) {
				builder.param(params[i], fill(params[i + 1], ids));
			}
			return builder;
		}

		private static String fill(String template, Map<String, String> ids) {
			String filled = template;
			for (Map.Entry<String, String> id : ids.entrySet()) {
				filled = filled.replace("{" + id.getKey() + "}", id.getValue());
			}
			return filled;
		}

	}

}
