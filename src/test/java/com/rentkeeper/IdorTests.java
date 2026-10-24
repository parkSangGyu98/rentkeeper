package com.rentkeeper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import java.time.Instant;
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
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.rentkeeper.common.crypto.SecureTokens;
import com.rentkeeper.landlord.application.LandlordService;
import com.rentkeeper.lease.application.LeaseChangeService;
import com.rentkeeper.lease.application.LeaseService;
import com.rentkeeper.lease.application.LeaseViews.LeaseRegistration;
import com.rentkeeper.lease.application.TenantLinkService;
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
			"/app/charges", "/app/dashboard", "/app/notifications");

	/**
	 * 남의 ID를 받지 않는 엔드포인트 (목록·새 화면·자기 건물 만들기). 여기에 넣을 때는 이유가 분명해야 한다. 받을 월세 목록은 달만
	 * 받고 로그인한 임대인 것만 보인다(chargeListShowsOnlyOwnCharges). 알림 이력도 로그인한 임대인 것만
	 * 보인다(notificationHistoryShowsOnlyOwnRows).
	 */
	private static final Set<String> NO_FOREIGN_ID = Set.of("GET /app/properties", "GET /app/properties/new",
			"POST /app/properties", "GET /app/charges", "GET /app/notifications");

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
	TenantLinkService tenantLinkService;

	@Autowired
	MutableClock clock;

	@Autowired
	@Qualifier("requestMappingHandlerMapping")
	RequestMappingHandlerMapping handlerMapping;

	Session attacker;

	Map<String, String> victimIds;

	/** 피해자 계약의 열린 세입자 링크 원문 (기간이 지나면 열리지 않아야 한다) */
	String victimToken;

	/** 피해자 계약의 닫힌 세입자 링크 원문 */
	String revokedVictimToken;

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
		// 피해자 계약의 세입자 링크: 닫힌 것 하나, 열린 것 하나 (공격자가 닫거나 바꾸지 못해야 한다)
		revokedVictimToken = tenantLinkService.issue(victim.landlordId(), lease, null).rawToken();
		long firstLink = jdbc.queryForObject("select id from tenant_access_token where lease_id = ?", Long.class,
				lease);
		victimToken = tenantLinkService.issue(victim.landlordId(), lease, firstLink).rawToken();
		jdbc.update("update lease set tenant_notify_paused_at = now() where id = ?", lease);
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
		long item = jdbc.queryForObject(
				"insert into charge_item (landlord_id, charge_id, type, label, amount, created_at, "
						+ "updated_at) values (?, ?, 'RENT', '월세', 500000, now(), now()) returning id",
				Long.class, victim.landlordId(), charge);
		victimIds = Map.of("id", "", "property", String.valueOf(property), "unit", String.valueOf(unit), "lease",
				String.valueOf(lease), "history", String.valueOf(history), "charge", String.valueOf(charge), "payment",
				String.valueOf(payment), "item", String.valueOf(item));
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
		// 세입자 링크 (M4). 열린 링크가 없다고 보내도(빈 값) 남의 계약이면 404
		attacks.put("POST /app/leases/{id}/tenant-link",
				Attack.post("/app/leases/{lease}/tenant-link", "openLinkId", ""));
		attacks.put("GET /app/leases/{id}/tenant-link/reissue", Attack.get("/app/leases/{lease}/tenant-link/reissue"));
		attacks.put("GET /app/leases/{id}/tenant-link/close", Attack.get("/app/leases/{lease}/tenant-link/close"));
		attacks.put("POST /app/leases/{id}/tenant-link/close",
				Attack.post("/app/leases/{lease}/tenant-link/close", "openLinkId", ""));
		// 세입자 알림 다시 켜기 (M5). 피해자 계약은 알림을 멈춘 상태라, 풀리면 snapshot이 달라진다
		attacks.put("POST /app/leases/{id}/tenant-notifications/resume",
				Attack.post("/app/leases/{lease}/tenant-notifications/resume"));
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
		attacks.put("GET /app/leases/{id}/charges/new",
				Attack.get("/app/leases/{lease}/charges/new", "month", "2026-10"));
		attacks.put("POST /app/leases/{id}/charges", Attack.post("/app/leases/{lease}/charges", "month", "2026-11",
				"monthlyRent", "1", "maintenanceFee", "0"));
		attacks.put("GET /app/charges/{id}/items/new", Attack.get("/app/charges/{charge}/items/new"));
		attacks.put("POST /app/charges/{id}/items", Attack.post("/app/charges/{charge}/items", "type", "OTHER", "label",
				"탈취", "amount", "1", "version", "0"));
		attacks.put("GET /app/charges/{id}/items/{itemId}/edit", Attack.get("/app/charges/{charge}/items/{item}/edit"));
		attacks.put("POST /app/charges/{id}/items/{itemId}", Attack.post("/app/charges/{charge}/items/{item}", "type",
				"RENT", "label", "탈취", "amount", "1", "version", "0"));
		attacks.put("GET /app/charges/{id}/items/{itemId}/delete",
				Attack.get("/app/charges/{charge}/items/{item}/delete"));
		attacks.put("POST /app/charges/{id}/items/{itemId}/delete",
				Attack.post("/app/charges/{charge}/items/{item}/delete", "version", "0"));
		// 대시보드의 건물 고르기 (M4)
		attacks.put("GET /app/dashboard", Attack.get("/app/dashboard", "propertyId", "{property}"));
		// 세입자 신고 반려 (M4)
		attacks.put("GET /app/charges/{id}/report/reject", Attack.get("/app/charges/{charge}/report/reject"));
		attacks.put("POST /app/charges/{id}/report/reject",
				Attack.post("/app/charges/{charge}/report/reject", "version", "0", "reason", "탈취"));
		attacks.put("GET /app/charges/{id}/cancel", Attack.get("/app/charges/{charge}/cancel"));
		attacks.put("POST /app/charges/{id}/cancel",
				Attack.post("/app/charges/{charge}/cancel", "version", "0", "reason", "탈취"));
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
	void ownChargeWithVictimsPaymentOrItemIdIsNotFound() throws Exception {
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
		for (Attack attack : List
				.of(Attack.get("/app/charges/{own}/payments/{payment}/delete"),
						Attack.get("/app/charges/{own}/payments/{payment}/correct"),
						Attack.post("/app/charges/{own}/payments/{payment}/delete", "version", "0", "reason", "섞기"),
						Attack.post("/app/charges/{own}/payments/{payment}/correct", "version", "0", "amount", "1",
								"paidOn", "2026-10-17", "reason", "섞기", "overpayConfirmed", "true"),
						Attack.get("/app/charges/{own}/items/{item}/edit"),
						Attack.post("/app/charges/{own}/items/{item}", "type", "RENT", "label", "섞기", "amount", "1",
								"version", "0"),
						Attack.post("/app/charges/{own}/items/{item}/delete", "version", "0"))) {
			assertThat(mockMvc.perform(attack.build(ids, attacker)).andReturn().getResponse().getStatus())
					.as(attack.path()).isEqualTo(404);
		}
		assertThat(snapshot()).as("자기 청구에 남의 입금·항목 ID를 섞어도 아무것도 바뀌지 않는다").isEqualTo(before);
	}

	@Test
	void chargeListShowsOnlyOwnCharges() throws Exception {
		String body = mockMvc.perform(Attack.get("/app/charges", "month", "2026-10").build(victimIds, attacker))
				.andReturn().getResponse().getContentAsString();
		assertThat(body).contains("이 달에 받을 월세가 없어요").doesNotContain("피해자세입자").doesNotContain("피해자빌라");
	}

	@Test
	void dashboardShowsOnlyOwnData() throws Exception {
		jdbc.update("update charge set status = 'OVERDUE' where landlord_id <> ?", attacker.landlordId());
		String body = mockMvc.perform(Attack.get("/app/dashboard").build(victimIds, attacker)).andReturn().getResponse()
				.getContentAsString();
		assertThat(body).contains("아직 등록한 건물이 없어요").doesNotContain("피해자");
	}

	@Test
	void notificationHistoryShowsOnlyOwnRows() throws Exception {
		long victimId = jdbc.queryForObject("select landlord_id from lease where id = ?", Long.class,
				Long.valueOf(victimIds.get("lease")));
		jdbc.update("insert into notification (landlord_id, type, channel, recipient_type, tenant_id, lease_id, "
				+ "charge_id, scheduled_for, dedupe_key, status, skip_reason, next_attempt_at, created_at, updated_at) "
				+ "select l.landlord_id, 'CHARGE_ISSUED', 'TENANT_PHONE', 'TENANT', l.tenant_id, l.id, ?, current_date, "
				+ "'CHARGE_ISSUED:victim', 'SKIPPED', 'CHANNEL_OFF', now(), now(), now() from lease l where l.id = ?",
				Long.valueOf(victimIds.get("charge")), Long.valueOf(victimIds.get("lease")));
		assertThat(victimId).isNotEqualTo(attacker.landlordId());

		String body = mockMvc.perform(Attack.get("/app/notifications").build(victimIds, attacker)).andReturn()
				.getResponse().getContentAsString();

		assertThat(body).contains("아직 알림이 없어요").doesNotContain("101호").doesNotContain("받을 월세 안내");
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

	// ---------------------------------------------------------------- 세입자 링크 (로그인
	// 없이 토큰으로만, 별도 표)

	/** 세입자 링크 엔드포인트의 경로 앞부분 */
	private static final String TENANT_LINK_PREFIX = "/t/";

	/**
	 * (메서드 경로) → 공격 경로. {token} 자리에 쓸 수 없는 토큰을 넣는다. 메서드를 정하지 않은 매핑은 "ANY"이고
	 * GET·POST 둘 다 보낸다.
	 */
	private static Map<String, String> tenantLinkAttacks() {
		Map<String, String> attacks = new LinkedHashMap<>();
		attacks.put("GET /t/{token}", "/t/{token}");
		attacks.put("GET /t/{token}/history", "/t/{token}/history");
		attacks.put("GET /t/{token}/charges/{chargeId}/report-paid", "/t/{token}/charges/{charge}/report-paid");
		attacks.put("POST /t/{token}/charges/{chargeId}/report-paid", "/t/{token}/charges/{charge}/report-paid");
		// 안내 문자 그만 받기·다시 받기 (M5). 쓸 수 없는 링크로는 세입자의 선택이 바뀌지 않는다(snapshot에 tenant 포함)
		attacks.put("GET /t/{token}/optout", "/t/{token}/optout");
		attacks.put("POST /t/{token}/optout", "/t/{token}/optout");
		attacks.put("POST /t/{token}/optin", "/t/{token}/optin");
		attacks.put("ANY /t/**", "/t/{token}/unknown");
		return attacks;
	}

	@Test
	void usableTenantLinkWithAnotherLeasesChargeIsNotFound() throws Exception {
		// 피해자의 다른 계약(같은 임대인)의 청구, 공격자의 청구
		long victimId = jdbc.queryForObject("select landlord_id from lease where id = ?", Long.class,
				Long.valueOf(victimIds.get("lease")));
		long property = Long.parseLong(victimIds.get("property"));
		long otherUnit = propertyService.addUnit(victimId, property, new UnitInput("102호", UnitType.ONE_ROOM, null));
		LeaseTerms terms = new LeaseTerms(0, 5, LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1), "국민은행",
				new AccountNumber("5555555555"), new PersonName("김임대"), null);
		long otherLease = leaseService.register(victimId, new LeaseRegistration(otherUnit, new PersonName("피해자이웃"),
				PhoneNumber.parse("01055556666"), null, 1, 0, terms, false));
		long otherLeaseCharge = charge(victimId, otherLease);
		long attackerProperty = propertyService.create(attacker.landlordId(), new PropertyInput("공격자빌라", "서울", null));
		long attackerUnit = propertyService.addUnit(attacker.landlordId(), attackerProperty,
				new UnitInput("201호", UnitType.ONE_ROOM, null));
		long attackerLease = leaseService.register(attacker.landlordId(), new LeaseRegistration(attackerUnit,
				new PersonName("공격자세입자"), PhoneNumber.parse("01099998888"), null, 1, 0, terms, false));
		long attackerCharge = charge(attacker.landlordId(), attackerLease);
		// 쓸 수 있는 링크를 열면 "마지막으로 연 날"이 기록된다(하루에 한 번). 그것은 미리 기록해 두고 청구가 그대로인지 본다
		mockMvc.perform(get("/t/" + victimToken));
		String before = snapshot();
		String notFound = mockMvc.perform(get("/t/" + SecureTokens.newToken())).andReturn().getResponse()
				.getContentAsString();

		List<String> failures = new ArrayList<>();
		for (long foreign : new long[]{otherLeaseCharge, attackerCharge}) {
			for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.POST)) {
				MvcResult result = mockMvc
						.perform(request(method, "/t/" + victimToken + "/charges/" + foreign + "/report-paid"))
						.andReturn();
				String label = method + " 청구 " + foreign;
				if (result.getResponse().getStatus() != 404) {
					failures.add(label + " → " + result.getResponse().getStatus() + " (404여야 함)");
				} else if (!result.getResponse().getContentAsString().equals(notFound)) {
					failures.add(label + " → 404 본문이 임의 토큰의 404와 다름");
				}
			}
		}

		assertThat(failures).isEmpty();
		assertThat(snapshot()).as("다른 계약의 청구는 하나도 바뀌지 않음").isEqualTo(before);
	}

	private long charge(long landlordId, long leaseId) {
		return jdbc.queryForObject("insert into charge (landlord_id, lease_id, billing_month, due_date, "
				+ "total_amount, status, source, issued_at, created_at, updated_at) values (?, ?, '2026-10-01', "
				+ "'2026-10-05', 1, 'ISSUED', 'AUTO', now(), now(), now()) returning id", Long.class, landlordId,
				leaseId);
	}

	@Test
	void tenantLinkEndpointsRejectEveryUnusableTokenTheSameWay() throws Exception {
		String before = snapshot();
		String notFound = mockMvc.perform(get("/t/" + SecureTokens.newToken())).andReturn().getResponse()
				.getContentAsString();
		Map<String, String> unusable = new LinkedHashMap<>();
		unusable.put("닫힌 링크", revokedVictimToken);
		unusable.put("임의 토큰", SecureTokens.newToken());
		unusable.put("형식 틀림", "abc");
		List<String> failures = new ArrayList<>();

		for (Map.Entry<String, String> endpoint : tenantLinkAttacks().entrySet()) {
			for (Map.Entry<String, String> token : unusable.entrySet()) {
				failures.addAll(tenantAttack(endpoint, token.getKey(), token.getValue(), notFound));
			}
		}
		// 기간이 지난 열린 링크 (피해자 계약 끝 2027-07-01 + 90일 = 2027-09-29까지)
		clock.set(Instant.parse("2027-09-29T15:00:00Z"));
		for (Map.Entry<String, String> endpoint : tenantLinkAttacks().entrySet()) {
			failures.addAll(tenantAttack(endpoint, "기간이 지난 링크", victimToken, notFound));
		}

		assertThat(failures).isEmpty();
		assertThat(snapshot()).as("세입자 링크로 아무것도 바뀌지 않음").isEqualTo(before);
	}

	@Test
	void ownTenantLinkShowsOnlyOwnLease() throws Exception {
		long property = propertyService.create(attacker.landlordId(), new PropertyInput("공격자빌라", "서울", null));
		long unit = propertyService.addUnit(attacker.landlordId(), property,
				new UnitInput("201호", UnitType.ONE_ROOM, null));
		LeaseTerms terms = new LeaseTerms(0, 5, LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1), "국민은행",
				new AccountNumber("9999999999"), new PersonName("공격자"), null);
		long own = leaseService.register(attacker.landlordId(), new LeaseRegistration(unit, new PersonName("공격자세입자"),
				PhoneNumber.parse("01099998888"), null, 1, 0, terms, false));
		String ownToken = tenantLinkService.issue(attacker.landlordId(), own, null).rawToken();

		String body = mockMvc.perform(get("/t/" + ownToken)).andReturn().getResponse().getContentAsString();

		assertThat(body).contains("공격자빌라").doesNotContain("피해자").doesNotContain("1234567890");
	}

	@Test
	void everyTenantLinkEndpointIsInTheTenantTable() {
		Set<String> mapped = new TreeSet<>();
		handlerMapping.getHandlerMethods().keySet().forEach(info -> info.getPatternValues().forEach(pattern -> {
			if (pattern.startsWith(TENANT_LINK_PREFIX)) {
				Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
				if (methods.isEmpty()) {
					mapped.add("ANY " + pattern);
				}
				methods.forEach(method -> mapped.add(method.name() + " " + pattern));
			}
		}));
		assertThat(mapped).as("세입자 링크 엔드포인트가 있어야 한다").isNotEmpty();
		assertThat(tenantLinkAttacks().keySet()).as("새 세입자 링크 엔드포인트는 tenantLinkAttacks() 표에 넣는다").containsAll(mapped);
		assertThat(mapped).as("표에 없어진 엔드포인트가 남아 있지 않다").containsAll(tenantLinkAttacks().keySet());
	}

	/** 쓸 수 없는 토큰으로 부르면 404이고, 본문은 임의 토큰의 404와 같고, 피해자 데이터가 없다. 공격자 쿠키는 영향이 없다 */
	private List<String> tenantAttack(Map.Entry<String, String> endpoint, String tokenName, String token,
			String notFound) throws Exception {
		List<String> failures = new ArrayList<>();
		String path = endpoint.getValue().replace("{token}", token).replace("{charge}", victimIds.get("charge"));
		List<HttpMethod> methods = endpoint.getKey().startsWith("ANY ")
				? List.of(HttpMethod.GET, HttpMethod.POST)
				: List.of(HttpMethod.valueOf(endpoint.getKey().substring(0, endpoint.getKey().indexOf(' '))));
		for (HttpMethod method : methods) {
			MvcResult result = mockMvc.perform(request(method, path).cookie(attacker.cookie())).andReturn();
			String label = endpoint.getKey() + " [" + tokenName + ", " + method + "]";
			if (result.getResponse().getStatus() != 404) {
				failures.add(label + " → " + result.getResponse().getStatus() + " (404여야 함)");
			} else if (!result.getResponse().getContentAsString().equals(notFound)) {
				failures.add(label + " → 404 본문이 임의 토큰의 404와 다름");
			}
			if (result.getResponse().getContentAsString().contains("피해자")) {
				failures.add(label + " → 피해자 데이터가 응답에 보임");
			}
		}
		return failures;
	}

	// ---------------------------------------------------------------- 도우미

	/** 소유 데이터 전체를 한 문자열로 (바뀌었는지 비교용) */
	private String snapshot() {
		StringBuilder all = new StringBuilder();
		for (String table : List.of("property", "unit", "tenant", "lease", "lease_rent_history", "charge",
				"charge_item", "payment_record", "charge_status_history", "charge_item_history",
				"tenant_access_token")) {
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
