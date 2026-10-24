package com.rentkeeper.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

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
 * 임대인 대시보드 (M4 커밋 4, DASH-1~4, 결정 14). 오늘 2026-10-17(서울).
 * <ul>
 * <li>행복빌라 101호 홍길동(나가는 날 2027-01-01, 76일 남음), 102호 김세입(2028-01-01), 103호
 * 이세입(나가는 날 2026-10-10이 지났는데 진행 중)</li>
 * <li>사랑빌라 201호: 계약 없음(공실)</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, MutableClockConfiguration.class})
class DashboardTests {

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

	long happy;

	long love;

	long hong;

	long kim;

	long lee;

	@BeforeEach
	void setUp() throws Exception {
		clock.set(MutableClockConfiguration.START);
		TestData.reset(jdbc);
		a = LandlordLogin.signUpAndLogin(mockMvc, landlordService, passwordEncoder, "a@example.com");
		happy = property("행복빌라");
		love = property("사랑빌라");
		hong = lease(unit(happy, "101호"), "홍길동", "01011112222", "2027-01-01");
		kim = lease(unit(happy, "102호"), "김세입", "01033334444", "2028-01-01");
		lee = lease(unit(happy, "103호"), "이세입", "01055556666", "2026-10-10");
		unit(love, "201호");

		charge(hong, "2026-10-01", "2026-10-25", 550_000, 0, "ISSUED");
		charge(kim, "2026-10-01", "2026-10-25", 500_000, 500_000, "PAID");
		long reported = charge(lee, "2026-10-01", "2026-10-05", 400_000, 0, "TENANT_REPORTED");
		jdbc.update("update charge set tenant_reported_at = '2026-10-16T01:00:00Z' where id = ?", reported);
		// 한 번 반려된 뒤 다시 알림 → 2번째 신고
		for (int i = 0; i < 2; i++) {
			jdbc.update(
					"insert into charge_status_history (landlord_id, charge_id, from_status, to_status, actor_type, "
							+ "actor_id, reason, created_at) values (?, ?, 'ISSUED', 'TENANT_REPORTED', 'TENANT', 1, '세입자 입금 신고', "
							+ "now())",
					a.landlordId(), reported);
		}
		charge(lee, "2026-09-01", "2026-09-25", 400_000, 100_000, "OVERDUE");
		charge(hong, "2026-08-01", "2026-08-25", 550_000, 0, "OVERDUE");
		long canceled = charge(kim, "2026-09-01", "2026-09-25", 999_000, 0, "ISSUED");
		jdbc.update("update charge set status = 'CANCELED', canceled_at = now(), cancel_reason = '잘못' where id = ?",
				canceled);
	}

	@Test
	void appHomeIsTheDashboardWithEverySection() throws Exception {
		String page = page("/app");

		assertThat(page).contains("한눈에 보기").contains("<option value=\"\">모든 건물</option>");
		assertThat(page).as("① 확인 기다리는 입금 신고").contains("확인 기다리는 입금 신고 <span>1건</span>").contains("행복빌라 103호 · 이세입님")
				.contains("<strong>2번째 신고</strong>").contains("10월 16일 알림");
		assertThat(page).as("② 이번 달 (취소 제외)").contains("받을 월세 <span>3건</span>")
				.contains("<dd class=\"inline\">1,450,000원</dd>").contains("<dd class=\"inline\">500,000원</dd>")
				.contains("950,000원");
		assertThat(page).as("③ 납부일 지남 (모든 달)").contains("납부일 지남 <span>2건</span>").contains("<strong>850,000원</strong>")
				.contains("<strong>53일 지남</strong>").contains("<strong>22일 지남</strong>");
		assertThat(page.indexOf("53일 지남")).as("오래 지난 것부터").isLessThan(page.indexOf("22일 지남"));
		assertThat(page).as("④ 곧 끝나는 계약").contains("90일 안에 끝나는 계약 <span>2건</span>").contains("76일 남음")
				.contains("나가는 날이 지났어요 (계약 종료가 필요해요)").doesNotContain("행복빌라 102호 · 김세입님");
		assertThat(page.indexOf("나가는 날이 지났어요")).as("나가는 날 순").isLessThan(page.indexOf("76일 남음"));
		assertThat(page).as("⑤ 공실").contains("호실 <span>4개</span> 중");
		assertThat(page).doesNotContain("999,000");
	}

	@Test
	void choosingAPropertyCountsOnlyItsUnits() throws Exception {
		String lovePage = page("/app/dashboard?propertyId=" + love);
		assertThat(lovePage).contains("selected=\"selected\">사랑빌라</option>").contains("확인 기다리는 입금 신고 <span>0건</span>")
				.contains("받을 월세 <span>0건</span>").contains("납부일 지남 <span>0건</span>")
				.contains("90일 안에 끝나는 계약 <span>0건</span>").contains("호실 <span>1개</span> 중 <strong>공실 1개</strong>")
				.doesNotContain("이세입님").contains("href=\"/app/properties/" + love + "\"");

		String happyPage = page("/app/dashboard?propertyId=" + happy);
		assertThat(happyPage).contains("확인 기다리는 입금 신고 <span>1건</span>").contains("받을 월세 <span>3건</span>")
				.contains("납부일 지남 <span>2건</span>").contains("호실 <span>3개</span> 중");
	}

	@Test
	void unknownOrBadPropertyIsNotFound() throws Exception {
		assertThat(status("/app/dashboard?propertyId=999999999")).isEqualTo(404);
		assertThat(status("/app/dashboard?propertyId=abc")).isEqualTo(404);
		assertThat(status("/app/dashboard?propertyId=")).as("빈 값은 모든 건물").isEqualTo(200);
	}

	@Test
	void landlordWithoutPropertiesSeesTheFirstStep() throws Exception {
		Session b = LandlordLogin.signUpAndLogin(mockMvc, landlordService, passwordEncoder, "b@example.com");
		String page = mockMvc.perform(get("/app").cookie(b.cookie())).andReturn().getResponse().getContentAsString();
		assertThat(page).contains("아직 등록한 건물이 없어요").contains("건물 등록하기").doesNotContain("이세입");
	}

	private String page(String path) throws Exception {
		return mockMvc.perform(get(path).cookie(a.cookie())).andReturn().getResponse().getContentAsString()
				.replaceAll("\\s+", " ");
	}

	private int status(String path) throws Exception {
		return mockMvc.perform(get(path).cookie(a.cookie())).andReturn().getResponse().getStatus();
	}

	private long property(String name) {
		return jdbc.queryForObject("insert into property (landlord_id, name, created_at, updated_at) "
				+ "values (?, ?, now(), now()) returning id", Long.class, a.landlordId(), name);
	}

	private long unit(long property, String name) {
		return jdbc.queryForObject(
				"insert into unit (landlord_id, property_id, name, type, created_at, updated_at) "
						+ "values (?, ?, ?, 'ONE_ROOM', now(), now()) returning id",
				Long.class, a.landlordId(), property, name);
	}

	private long lease(long unit, String tenant, String phone, String end) {
		LeaseTerms terms = new LeaseTerms(0, 25, LocalDate.parse("2026-01-01"), LocalDate.parse(end), "국민은행",
				new AccountNumber("1234567890"), new PersonName("김임대"), null);
		return leaseService.register(a.landlordId(), new LeaseRegistration(unit, new PersonName(tenant),
				PhoneNumber.parse(phone), null, 500_000, 50_000, terms, false));
	}

	private long charge(long lease, String month, String due, long total, long paid, String status) {
		return jdbc.queryForObject("insert into charge (landlord_id, lease_id, billing_month, due_date, total_amount, "
				+ "paid_amount, status, source, issued_at, paid_at, tenant_reported_at, created_at, updated_at) values "
				+ "(?, ?, cast(? as date), cast(? as date), ?, ?, ?, 'AUTO', now(), case when ? = 'PAID' then now() end, "
				+ "case when ? = 'TENANT_REPORTED' then now() end, now(), now()) returning id", Long.class,
				a.landlordId(), lease, month, due, total, paid, status, status, status);
	}

}
