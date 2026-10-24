package com.rentkeeper.billing;

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
 * 받을 월세 목록: 합계(취소 제외), 상태 글자, 더 받은 금액, 한 달 안 세입자 교체 안내(M3 결정 3), 빈 상태, 달 이동, 상태
 * 고르기(M3-b 결정 12). 오늘은 2026-10-17(서울).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, MutableClockConfiguration.class})
class ChargeListTests {

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

	long unit101;

	long unit102;

	@BeforeEach
	void setUp() throws Exception {
		clock.set(MutableClockConfiguration.START);
		TestData.reset(jdbc);
		a = LandlordLogin.signUpAndLogin(mockMvc, landlordService, passwordEncoder, "a@example.com");
		long property = jdbc.queryForObject("insert into property (landlord_id, name, created_at, updated_at) "
				+ "values (?, '행복빌라', now(), now()) returning id", Long.class, a.landlordId());
		unit101 = unit(property, "101호");
		unit102 = unit(property, "102호");
	}

	@Test
	void showsMonthWithTotalsStatusesAndOverpayment() throws Exception {
		long kim = lease(unit101, "김세입", "01011112222", "2026-01-01", "2027-01-01");
		long lee = lease(unit102, "이세입", "01033334444", "2026-01-01", "2027-01-01");
		charge(kim, "2026-10-01", "2026-10-05", 550_000, 600_000, "PAID");
		charge(lee, "2026-10-01", "2026-10-25", 400_000, 100_000, "PARTIALLY_PAID");
		long canceled = charge(lee, "2026-09-01", "2026-09-25", 400_000, 0, "ISSUED");
		jdbc.update("update charge set status = 'CANCELED', canceled_at = now(), cancel_reason = '잘못 만듦' where id = ?",
				canceled);

		String page = page("/app/charges");
		assertThat(page).contains("<span>2026년 10월</span> 받을 월세").contains("이번 달").contains("행복빌라 101호")
				.contains("김세입님").contains("10월 5일").contains("다 받음").contains("이세입님").contains("일부 받음")
				.contains("<dt class=\"stat-label\">더 받은 돈</dt> <dd class=\"row-value\">50,000원</dd>");
		// 받을 돈 95만, 받은 돈 70만, 남은 돈 30만 (더 받은 5만은 다른 청구의 남은 돈을 줄이지 않는다)
		assertThat(page).contains("<dd class=\"stat-value\">950,000원</dd>")
				.contains("<dd class=\"stat-value\">700,000원</dd>").contains("300,000원");

		assertThat(page("/app/charges?month=2026-09")).as("취소한 청구도 보이지만 합계에서 빠진다").contains("취소함")
				.contains("<dd class=\"stat-value\">0원</dd>");
	}

	@Test
	void marksMonthBilledToTwoLeasesOfSameUnit() throws Exception {
		// 3/16 나가고 같은 날 다음 세입자가 들어온 경우 (M3 결정 3)
		long before = lease(unit101, "김세입", "01011112222", "2025-03-16", "2026-03-16");
		long after = lease(unit101, "이세입", "01033334444", "2026-03-16", "2027-03-16");
		charge(before, "2026-03-01", "2026-03-05", 500_000, 0, "ISSUED");
		charge(after, "2026-03-01", "2026-03-05", 500_000, 0, "ISSUED");

		assertThat(page("/app/charges?month=2026-03")).contains("이 달은 두 계약 모두 청구됐어요");
	}

	@Test
	void statusFilterShowsMatchingRowsButKeepsMonthTotalsAndCounts() throws Exception {
		long kim = lease(unit101, "김세입", "01011112222", "2026-01-01", "2027-01-01");
		long lee = lease(unit102, "이세입", "01033334444", "2026-01-01", "2027-01-01");
		charge(kim, "2026-10-01", "2026-10-05", 550_000, 550_000, "PAID");
		charge(lee, "2026-10-01", "2026-10-25", 400_000, 100_000, "PARTIALLY_PAID");
		jdbc.update("update charge set status = 'TENANT_REPORTED', tenant_reported_at = now() "
				+ "where status = 'PARTIALLY_PAID'");

		String paid = page("/app/charges?month=2026-10&status=paid");
		assertThat(paid).contains("김세입님").doesNotContain("이세입님").as("합계는 고른 상태와 관계없이 달 전체")
				.contains("<dd class=\"stat-value\">950,000원</dd>").contains("<dd class=\"stat-value\">650,000원</dd>");
		assertThat(paid).contains(">전체 2</a>").contains(">확인 기다림 1</a>").contains(">받을 차례 0</a>")
				.contains(">일부 받음 0</a>").contains(">다 받음 1</a>").contains(">납부일 지남 0</a>").contains(">취소함 0</a>")
				.contains("aria-current=\"page\"");
		assertThat(paid).as("달을 옮겨도 고른 상태는 그대로").contains("href=\"/app/charges?month=2026-09&amp;status=paid\"")
				.contains("href=\"/app/charges?month=2026-10&amp;status=partially-paid\"");

		assertThat(page("/app/charges?month=2026-10&status=reported")).as("세입자 신고는 확인 기다림 칸 (M4 결정 13)")
				.contains("이세입님").doesNotContain("김세입님");
		assertThat(page("/app/charges?month=2026-10&status=reported")).as("상태 뱃지는 탭과 같은 이름")
				.contains("<span>확인 기다림</span>");
		assertThat(page("/app/charges?month=2026-10&status=issued")).as("받을 차례에서는 빠진다").contains("이 상태인 받을 월세가 없어요");
		assertThat(page("/app/charges?month=2026-10&status=overdue")).contains("이 상태인 받을 월세가 없어요")
				.doesNotContain("이 달에 받을 월세가 없어요");
		assertThat(page("/app/charges?month=2026-10&status=nope")).as("모르는 값이면 전체").contains("김세입님").contains("이세입님");
	}

	@Test
	void emptyMonthAndNavigation() throws Exception {
		String page = page("/app/charges?month=2026-12");
		assertThat(page).contains("이 달에 받을 월세가 없어요").contains("href=\"/app/charges?month=2026-11\"")
				.contains("href=\"/app/charges?month=2027-01\"").contains("href=\"/app/charges\"");

		assertThat(page("/app/charges?month=not-a-month")).as("잘못된 달이면 이번 달").contains("<span>2026년 10월</span> 받을 월세");
	}

	private String page(String path) throws Exception {
		return mockMvc.perform(get(path).cookie(a.cookie())).andReturn().getResponse().getContentAsString()
				.replaceAll("\\s+", " ");
	}

	private long unit(long property, String name) {
		return jdbc.queryForObject(
				"insert into unit (landlord_id, property_id, name, type, created_at, updated_at) "
						+ "values (?, ?, ?, 'ONE_ROOM', now(), now()) returning id",
				Long.class, a.landlordId(), property, name);
	}

	private long lease(long unit, String tenant, String phone, String start, String end) {
		LeaseTerms terms = new LeaseTerms(0, 5, LocalDate.parse(start), LocalDate.parse(end), "국민은행",
				new AccountNumber("1234567890"), new PersonName("김임대"), null);
		return leaseService.register(a.landlordId(), new LeaseRegistration(unit, new PersonName(tenant),
				PhoneNumber.parse(phone), null, 500_000, 50_000, terms, false));
	}

	private long charge(long lease, String month, String due, long total, long paid, String status) {
		return jdbc.queryForObject("insert into charge (landlord_id, lease_id, billing_month, due_date, total_amount, "
				+ "paid_amount, status, source, issued_at, paid_at, created_at, updated_at) values (?, ?, cast(? as date), "
				+ "cast(? as date), ?, ?, ?, 'AUTO', now(), case when ? = 'PAID' then now() end, now(), now()) "
				+ "returning id", Long.class, a.landlordId(), lease, month, due, total, paid, status, status);
	}

}
