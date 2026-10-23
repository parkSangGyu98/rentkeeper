package com.rentkeeper.tenantlink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.rentkeeper.TestcontainersConfiguration;
import com.rentkeeper.common.crypto.SecureTokens;
import com.rentkeeper.landlord.application.LandlordService;
import com.rentkeeper.lease.application.LeaseService;
import com.rentkeeper.lease.application.LeaseViews.LeaseRegistration;
import com.rentkeeper.lease.application.TenantLinkService;
import com.rentkeeper.lease.domain.AccountNumber;
import com.rentkeeper.lease.domain.LeaseTerms;
import com.rentkeeper.lease.domain.PersonName;
import com.rentkeeper.lease.domain.PhoneNumber;
import com.rentkeeper.lease.domain.PrivateMemo;
import com.rentkeeper.support.LandlordLogin;
import com.rentkeeper.support.LandlordLogin.Session;
import com.rentkeeper.support.MutableClock;
import com.rentkeeper.support.MutableClockConfiguration;
import com.rentkeeper.support.TestData;

/**
 * 세입자 링크 화면 (M4 커밋 2, 결정 5·6·8·9, ADR-026). 계약 2026-01-01 ~ 2027-01-01(받는 날
 * 5일), 오늘 2026-10-17(서울).
 * <ul>
 * <li>보이는 것: 이번 달·덜 확인된 지난 달·다음 달 받을 월세, 계좌번호 전체, 가린 이름·예금주. 안 보이는 것: 전화번호,
 * 보증금, 메모, 다 확인된 지난 달, 취소한 달</li>
 * <li>보안: no-referrer, 색인 금지, 캐시 금지, 세션 쿠키 없음, 외부 주소 없음, 틀린 링크는 모두 같은 404, IP당
 * 1분 30회, 로그에 토큰 없음</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, MutableClockConfiguration.class})
@ExtendWith(OutputCaptureExtension.class)
class TenantPageTests {

	/** 외부 주소를 부르는 속성 (http:, https:, 프로토콜 생략 //) */
	private static final Pattern EXTERNAL = Pattern
			.compile("(?i)(src|href|action|content|srcset)\\s*=\\s*\"\\s*(https?:)?//");

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
	TenantLinkService tenantLinkService;

	@Autowired
	MutableClock clock;

	Session a;

	long lease;

	String token;

	@BeforeEach
	void setUp() throws Exception {
		clock.set(MutableClockConfiguration.START);
		TestData.reset(jdbc);
		a = LandlordLogin.signUpAndLogin(mockMvc, landlordService, passwordEncoder, "a@example.com");
		long property = jdbc.queryForObject("insert into property (landlord_id, name, created_at, updated_at) "
				+ "values (?, '행복빌라', now(), now()) returning id", Long.class, a.landlordId());
		long unit = jdbc.queryForObject(
				"insert into unit (landlord_id, property_id, name, type, memo, created_at, "
						+ "updated_at) values (?, ?, '101호', 'ONE_ROOM', '호실비밀메모', now(), now()) returning id",
				Long.class, a.landlordId(), property);
		LeaseTerms terms = new LeaseTerms(10_000_000, 5, LocalDate.parse("2026-01-01"), LocalDate.parse("2027-01-01"),
				"국민은행", new AccountNumber("1234567890"), new PersonName("김임대"), "계약비밀메모");
		lease = leaseService.register(a.landlordId(), new LeaseRegistration(unit, new PersonName("홍길동"),
				PhoneNumber.parse("01011112222"), new PrivateMemo("세입자비밀메모"), 500_000, 50_000, terms, false));
		token = tenantLinkService.issue(a.landlordId(), lease, null).rawToken();
	}

	@Test
	void showsChargesToSendAccountAndMaskedNamesOnly() throws Exception {
		long october = charge("2026-10-01", "2026-10-05", 550_000, 0, "ISSUED");
		item(october, "RENT", "월세", 500_000);
		item(october, "MAINTENANCE", "관리비", 50_000);
		charge("2026-09-01", "2026-09-05", 550_000, 550_000, "PAID");
		charge("2026-08-01", "2026-08-05", 550_000, 100_000, "OVERDUE");
		long july = charge("2026-07-01", "2026-07-05", 550_000, 0, "ISSUED");
		jdbc.update("update charge set status = 'CANCELED', canceled_at = now(), cancel_reason = '취소비밀사유' "
				+ "where id = ?", july);
		charge("2026-11-01", "2026-11-05", 550_000, 0, "ISSUED");

		String page = page("/t/" + token);

		assertThat(page).contains("행복빌라 101호").contains("<span>홍*동</span>님, 안녕하세요.").contains("10월 월세")
				.contains("<strong>10월 5일까지</strong>").contains("550,000원").contains("<span>월세</span>")
				.contains("<span>관리비</span>").contains("아직 확인 전이에요");
		assertThat(page).as("덜 확인된 지난 달").contains("8월 월세").contains("<strong>100,000원</strong>이 확인됐어요")
				.contains("450,000원");
		assertThat(page).as("다음 달").contains("11월 월세");
		assertThat(page).as("계좌번호 전체, 가린 예금주").contains("1234567890").contains("예금주 <span>김*대</span>");
		assertThat(page).as("다 확인된 지난 달·취소한 달은 없다").doesNotContain("9월 월세").doesNotContain("7월 월세");
		assertThat(page).as("개인정보·임대인 글이 없다").doesNotContain("홍길동").doesNotContain("김임대").doesNotContain("01011112222")
				.doesNotContain("010-1111-2222").doesNotContain("10,000,000").doesNotContain("비밀메모")
				.doesNotContain("취소비밀사유");
		assertThat(page).as("임대인 메뉴가 없다").doesNotContain("로그아웃").doesNotContain("내 건물");
	}

	@Test
	void historyShowsLastTwelveMonthsWithoutCanceled() throws Exception {
		charge("2026-10-01", "2026-10-05", 550_000, 0, "ISSUED");
		charge("2026-09-01", "2026-09-05", 550_000, 550_000, "PAID");
		long august = charge("2026-08-01", "2026-08-05", 550_000, 0, "ISSUED");
		jdbc.update("update charge set status = 'CANCELED', canceled_at = now(), cancel_reason = '잘못' where id = ?",
				august);
		charge("2025-10-01", "2025-10-05", 550_000, 550_000, "PAID");

		String page = page("/t/" + token + "/history");

		assertThat(page).contains("2026년 10월").contains("2026년 9월").contains("입금이 확인됐어요").doesNotContain("2026년 8월")
				.as("12개월보다 오래된 달").doesNotContain("2025년 10월");
		assertThat(page.indexOf("2026년 10월")).as("최근 달이 위").isLessThan(page.indexOf("2026년 9월"));
	}

	@Test
	void securityHeadersNoSessionCookieAndNoLandlordSession() throws Exception {
		MvcResult result = mockMvc.perform(get("/t/" + token).cookie(a.cookie())).andReturn();

		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		assertThat(result.getResponse().getHeader("Referrer-Policy")).isEqualTo("no-referrer");
		assertThat(result.getResponse().getHeader("X-Robots-Tag")).isEqualTo("noindex, nofollow, noarchive");
		assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
		assertThat(result.getResponse().getHeader("Content-Security-Policy")).contains("default-src 'self'");
		assertThat(result.getResponse().getHeaders("Set-Cookie")).as("세션 쿠키를 만들지 않는다").isEmpty();
		assertThat(result.getResponse().getContentAsString()).as("임대인으로 로그인한 브라우저에서도 임대인 화면이 섞이지 않는다")
				.doesNotContain("로그아웃").contains("<meta name=\"robots\" content=\"noindex, nofollow, noarchive\">");
	}

	@Test
	void pagesHaveNoExternalUrls() throws Exception {
		charge("2026-10-01", "2026-10-05", 550_000, 0, "ISSUED");
		List<String> bodies = List.of(page("/t/" + token), page("/t/" + token + "/history"),
				page("/t/" + SecureTokens.newToken()));
		for (int i = 0; i < 40; i++) {
			mockMvc.perform(get("/t/x").with(ip("10.9.9.9")));
		}
		String tooMany = mockMvc.perform(get("/t/x").with(ip("10.9.9.9"))).andReturn().getResponse()
				.getContentAsString();

		for (String body : List.of(bodies.get(0), bodies.get(1), bodies.get(2), tooMany)) {
			assertThat(EXTERNAL.matcher(body).find()).as(body).isFalse();
			assertThat(body).doesNotContain("http://").doesNotContain("https://");
		}
	}

	@Test
	void everyUnusableLinkLooksTheSame() throws Exception {
		MvcResult reference = mockMvc.perform(get("/t/" + SecureTokens.newToken())).andReturn();
		assertThat(reference.getResponse().getStatus()).isEqualTo(404);
		String notFound = reference.getResponse().getContentAsString();
		assertThat(notFound).contains("링크가 맞지 않거나 기간이 지났어요").contains("집주인에게 새 링크를 요청해 주세요");

		String revoked = token;
		long open = jdbc.queryForObject("select id from tenant_access_token where revoked_at is null", Long.class);
		String reissued = tenantLinkService.issue(a.landlordId(), lease, open).rawToken();

		assertSameNotFound(get("/t/abc"), notFound);
		assertSameNotFound(get("/t/" + revoked), notFound);
		assertSameNotFound(get("/t/" + revoked + "/history"), notFound);
		assertSameNotFound(get("/t/" + reissued + "/nope/nope"), notFound);
		assertSameNotFound(post("/t/" + reissued), notFound);
		assertSameNotFound(get("/t/"), notFound);

		clock.set(Instant.parse("2027-04-01T15:00:00Z"));
		assertSameNotFound(get("/t/" + reissued), notFound);
		clock.set(Instant.parse("2027-04-01T14:59:59Z"));
		assertThat(mockMvc.perform(get("/t/" + reissued)).andReturn().getResponse().getStatus())
				.as("마지막 날(서울 2027-04-01)까지는 열린다").isEqualTo(200);
	}

	@Test
	void lastOpenedIsRecordedOncePerDayAndShownToLandlord() throws Exception {
		page("/t/" + token);
		Instant first = lastUsed();
		assertThat(first).isEqualTo(MutableClockConfiguration.START);

		clock.advance(Duration.ofHours(1));
		page("/t/" + token);
		assertThat(lastUsed()).as("같은 날은 다시 쓰지 않는다").isEqualTo(first);

		clock.advance(Duration.ofDays(1));
		page("/t/" + token);
		assertThat(lastUsed()).isEqualTo(MutableClockConfiguration.START.plus(Duration.ofHours(25)));
		assertThat(mockMvc.perform(get("/app/leases/" + lease).cookie(a.cookie())).andReturn().getResponse()
				.getContentAsString()).contains("2026년 10월 18일");
	}

	@Test
	void thirtyRequestsPerMinutePerIpCountingWrongTokens() throws Exception {
		for (int i = 0; i < 20; i++) {
			assertThat(status(get("/t/" + token).with(ip("10.0.0.1")))).isEqualTo(200);
		}
		for (int i = 0; i < 10; i++) {
			assertThat(status(get("/t/" + SecureTokens.newToken()).with(ip("10.0.0.1")))).isEqualTo(404);
		}

		MvcResult blocked = mockMvc.perform(get("/t/" + token).with(ip("10.0.0.1"))).andReturn();
		assertThat(blocked.getResponse().getStatus()).isEqualTo(429);
		assertThat(blocked.getResponse().getContentAsString()).contains("잠시 뒤에 열어 주세요").doesNotContain("로그인");
		assertThat(status(get("/t/" + token).with(ip("10.0.0.2")))).as("다른 IP는 따로 센다").isEqualTo(200);

		clock.advance(Duration.ofMinutes(1));
		assertThat(status(get("/t/" + token).with(ip("10.0.0.1")))).as("1분이 지나면 다시 열린다").isEqualTo(200);
	}

	@Test
	void logsNeverContainTheToken(CapturedOutput output) throws Exception {
		String revoked = token;
		long open = jdbc.queryForObject("select id from tenant_access_token where revoked_at is null", Long.class);
		String current = tenantLinkService.issue(a.landlordId(), lease, open).rawToken();
		String unknown = SecureTokens.newToken();

		page("/t/" + current);
		page("/t/" + current + "/history");
		page("/t/" + revoked);
		page("/t/" + unknown);
		for (int i = 0; i < 31; i++) {
			mockMvc.perform(get("/t/" + current + "/nope").with(ip("10.0.0.3")));
		}

		assertThat(output.getAll()).doesNotContain(current).doesNotContain(revoked).doesNotContain(unknown);
	}

	private void assertSameNotFound(MockHttpServletRequestBuilder request, String notFound) throws Exception {
		MvcResult result = mockMvc.perform(request).andReturn();
		assertThat(result.getResponse().getStatus()).isEqualTo(404);
		assertThat(result.getResponse().getContentAsString()).isEqualTo(notFound);
	}

	private int status(MockHttpServletRequestBuilder request) throws Exception {
		return mockMvc.perform(request).andReturn().getResponse().getStatus();
	}

	private static org.springframework.test.web.servlet.request.RequestPostProcessor ip(String address) {
		return request -> {
			request.setRemoteAddr(address);
			return request;
		};
	}

	private Instant lastUsed() {
		return jdbc.queryForObject("select last_used_at from tenant_access_token where revoked_at is null",
				java.sql.Timestamp.class).toInstant();
	}

	private String page(String path) throws Exception {
		return mockMvc.perform(get(path)).andReturn().getResponse().getContentAsString().replaceAll("\\s+", " ");
	}

	private long charge(String month, String due, long total, long paid, String status) {
		return jdbc.queryForObject("insert into charge (landlord_id, lease_id, billing_month, due_date, total_amount, "
				+ "paid_amount, status, source, issued_at, paid_at, created_at, updated_at) values (?, ?, cast(? as date), "
				+ "cast(? as date), ?, ?, ?, 'AUTO', now(), case when ? = 'PAID' then now() end, now(), now()) "
				+ "returning id", Long.class, a.landlordId(), lease, month, due, total, paid, status, status);
	}

	private void item(long charge, String type, String label, long amount) {
		jdbc.update("insert into charge_item (landlord_id, charge_id, type, label, amount, created_at, updated_at) "
				+ "values (?, ?, ?, ?, ?, now(), now())", a.landlordId(), charge, type, label, amount);
	}

}
