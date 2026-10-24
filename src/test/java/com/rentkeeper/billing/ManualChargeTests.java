package com.rentkeeper.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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
import org.springframework.test.web.servlet.MvcResult;

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
 * 받을 월세 직접 만들기와 계약 상세 배너 (M3-b 커밋 3, M3 결정 2·8, M3-b 결정 7·8·9·11). 오늘은
 * 2026-10-17(서울), 계약은 오늘 등록, 받는 날 5일, 월세 50만 + 관리비 5만.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, MutableClockConfiguration.class})
class ManualChargeTests {

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
	void bannerButtonLeadsToPrefilledFormAndCreatesIssuedCharge() throws Exception {
		long lease = lease("2026-10-01", "2027-10-01");
		String detail = page("/app/leases/" + lease);
		assertThat(detail).contains("이번 달 받을 월세가 아직 없어요").contains("이번 달 납부일(<span>10월 5일</span>)이 지났는데")
				.contains("href=\"/app/leases/" + lease + "/charges/new?month=2026-10\"");

		String form = page("/app/leases/" + lease + "/charges/new?month=2026-10");
		assertThat(form).contains("2026년 10월</span> 받을 월세").contains("2026년 10월 5일")
				.contains("name=\"monthlyRent\" value=\"500000\"").contains("name=\"maintenanceFee\" value=\"50000\"");

		MvcResult created = create(lease, "2026-10", "500,000", "50,000");
		long charge = jdbc.queryForObject("select id from charge where lease_id = ?", Long.class, lease);
		assertThat(created.getResponse().getRedirectedUrl()).isEqualTo("/app/charges/" + charge);
		assertThat(created.getFlashMap().get("notice")).isEqualTo("2026년 10월 받을 월세를 만들었어요.");

		assertThat(jdbc.queryForMap("select source, status, total_amount from charge where id = ?", charge))
				.containsEntry("source", "MANUAL").containsEntry("status", "OVERDUE")
				.containsEntry("total_amount", 550_000L);
		assertThat(jdbc.queryForList(
				"select to_status, actor_type, reason from charge_status_history where charge_id = ? " + "order by id",
				charge)).extracting(r -> r.get("to_status"), r -> r.get("actor_type"), r -> r.get("reason"))
				.containsExactly(tuple("SCHEDULED", "LANDLORD", "직접 만듦"), tuple("ISSUED", "LANDLORD", "발행"),
						tuple("OVERDUE", "LANDLORD", "납부일 지남"));
		assertThat(page("/app/leases/" + lease)).doesNotContain("이번 달 받을 월세가 아직 없어요");
	}

	@Test
	void amountsCanBeEditedAtCreation() throws Exception {
		long lease = lease("2026-10-16", "2027-10-16");

		create(lease, "2026-10", "250,000", "");

		assertThat(jdbc.queryForObject("select total_amount from charge", Long.class)).isEqualTo(250_000L);
		assertThat(jdbc.queryForList("select item_type, after_amount, actor_type from charge_item_history"))
				.extracting(r -> r.get("item_type"), r -> r.get("after_amount"), r -> r.get("actor_type"))
				.containsExactly(tuple("RENT", 250_000L, "LANDLORD"));
	}

	@Test
	void bannerCountsOnlyRecentThreeMonthsButPickerOffersOlderOnes() throws Exception {
		// 6월에 들어왔고 오늘(10/17) 등록: 6·7·8·9·10월 모두 받을 월세가 없다
		long lease = lease("2026-06-01", "2027-06-01");

		String detail = page("/app/leases/" + lease);
		assertThat(detail).contains("받을 월세가 없는 지난 달 <strong>3개</strong>").contains("2026년 7월, 8월, 9월")
				.doesNotContain("2026년 6월, 7월").contains("다른 달 받을 월세 만들기");

		String picker = page("/app/leases/" + lease + "/charges/new");
		assertThat(picker).as("3개월보다 오래된 6월은 달 고르기 화면에서 만든다").contains("month=2026-06").contains("month=2026-09")
				.contains("month=2026-10").contains("month=2026-11").doesNotContain("month=2026-12")
				.doesNotContain("month=2026-05");

		// 7월(이번 달 − 3)을 만들면 남은 지난 달은 2개, 이번 달을 만들면 제목이 바뀐다
		create(lease, "2026-07", "500000", "50000");
		create(lease, "2026-10", "500000", "50000");
		assertThat(page("/app/leases/" + lease)).contains("받을 월세가 없는 지난 달 <strong>2개</strong>").contains("2026년 8월, 9월")
				.contains("받을 월세가 없는 지난 달이 있어요").doesNotContain("이번 달 받을 월세가 아직 없어요");
	}

	@Test
	void monthRulesExistsCanceledOutOfRangeAndZero() throws Exception {
		long lease = lease("2026-09-01", "2027-09-01");
		create(lease, "2026-09", "500000", "50000");
		jdbc.update("update charge set status = 'CANCELED', canceled_at = now(), cancel_reason = '잘못 만듦'");
		create(lease, "2026-10", "500000", "50000");

		assertThat(body(create(lease, "2026-10", "500000", "0"))).contains("이 달 받을 월세는 이미 있어요");
		assertThat(body(create(lease, "2026-09", "500000", "0"))).contains("이 달 받을 월세는 취소한 적이 있어 새로 만들 수 없어요.");
		assertThat(page("/app/leases/" + lease + "/charges/new?month=2026-12"))
				.contains("계약 기간 안의 달(다음 달까지)만 만들 수 있어요.");
		assertThat(body(create(lease, "2026-08", "500000", "0"))).contains("계약 기간 안의 달(다음 달까지)만 만들 수 있어요.");
		assertThat(body(create(lease, "2026-11", "0", ""))).contains("월세와 관리비가 모두 0원이에요. 받을 금액을 입력해 주세요.");
		assertThat(body(create(lease, "2026-11", "오십만", ""))).contains("월세를 숫자로 입력해 주세요");
		assertThat(body(create(lease, "2026-11", "100,000,001", ""))).contains("1억 원까지 입력할 수 있어요");
		assertThat(jdbc.queryForObject("select count(*) from charge", Integer.class)).isEqualTo(2);

		create(lease, "2026-11", "500000", "50000");
		assertThat(jdbc.queryForObject("select status from charge where billing_month = '2026-11-01'", String.class))
				.as("다음 달은 만들 수 있고 납부일 전이라 받을 차례").isEqualTo("ISSUED");
	}

	@Test
	void endedLeaseCanCreateMonthsWithinItsPeriod() throws Exception {
		long lease = lease("2026-08-01", "2027-08-01");
		jdbc.update("update lease set status = 'ENDED', ended_on = '2026-09-10' where id = ?", lease);

		assertThat(page("/app/leases/" + lease + "/charges/new")).contains("month=2026-08").contains("month=2026-09")
				.doesNotContain("month=2026-10");
		create(lease, "2026-09", "500000", "0");
		assertThat(jdbc.queryForObject("select count(*) from charge", Integer.class)).isEqualTo(1);
	}

	@Test
	void monthCreatedMeanwhileByBatchShowsExistsGuide() throws Exception {
		long lease = lease("2026-10-01", "2027-10-01");
		page("/app/leases/" + lease + "/charges/new?month=2026-10");
		// 화면을 보는 사이 다른 곳(배치·다른 창)에서 같은 달을 만들었다
		jdbc.update("insert into charge (landlord_id, lease_id, billing_month, due_date, total_amount, status, source, "
				+ "issued_at, created_at, updated_at) values (?, ?, '2026-10-01', '2026-10-05', 550000, 'ISSUED', 'AUTO', "
				+ "now(), now(), now())", a.landlordId(), lease);

		assertThat(body(create(lease, "2026-10", "500000", "50000"))).contains("이 달 받을 월세는 이미 있어요");
		assertThat(jdbc.queryForObject("select count(*) from charge", Integer.class)).isEqualTo(1);
	}

	private long lease(String start, String end) {
		LeaseTerms terms = new LeaseTerms(0, 5, LocalDate.parse(start), LocalDate.parse(end), "국민은행",
				new AccountNumber("1234567890"), new PersonName("김임대"), null);
		return leaseService.register(a.landlordId(), new LeaseRegistration(unit, new PersonName("홍길동"),
				PhoneNumber.parse("01011112222"), null, 500_000, 50_000, terms, false));
	}

	private MvcResult create(long lease, String month, String rent, String fee) throws Exception {
		return mockMvc.perform(post("/app/leases/" + lease + "/charges").with(csrf()).cookie(a.cookie())
				.param("month", month).param("monthlyRent", rent).param("maintenanceFee", fee)).andReturn();
	}

	private String page(String path) throws Exception {
		return mockMvc.perform(get(path).cookie(a.cookie())).andReturn().getResponse().getContentAsString()
				.replaceAll("\\s+", " ");
	}

	private static String body(MvcResult result) throws Exception {
		return result.getResponse().getContentAsString();
	}

}
