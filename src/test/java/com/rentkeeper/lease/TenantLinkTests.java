package com.rentkeeper.lease;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
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

import com.rentkeeper.TestcontainersConfiguration;
import com.rentkeeper.common.crypto.SecureTokens;
import com.rentkeeper.landlord.application.LandlordService;
import com.rentkeeper.lease.application.LeaseExceptions.TenantLinkStaleException;
import com.rentkeeper.lease.application.LeaseService;
import com.rentkeeper.lease.application.LeaseViews.LeaseRegistration;
import com.rentkeeper.lease.application.TenantLinkService;
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
 * 세입자 링크 만들기·새로 만들기·닫기 (M4 커밋 1, 결정 1~4, ADR-026). 계약 기간 2026-01-01 ~
 * 2027-01-01, 오늘 2026-10-17(서울).
 * <ul>
 * <li>원문은 만든 화면에만 보이고 DB·세션·flash·로그에 남지 않는다(해시만 저장)</li>
 * <li>화면이 보던 열린 링크 ID와 다르면(새로고침 재전송) 새로 만들지 않고, 방금 만든 링크도 닫히지 않는다</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, MutableClockConfiguration.class})
@ExtendWith(OutputCaptureExtension.class)
class TenantLinkTests {

	private static final Pattern LINK = Pattern.compile("http://localhost:8080/t/([A-Za-z0-9_-]{43})");

	private static final String STALE_ISSUE = "이미 새 링크가 만들어졌어요. 방금 만든 링크는 다시 볼 수 없어요. 필요하면 새로 만들어 주세요";

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
		LeaseTerms terms = new LeaseTerms(0, 5, LocalDate.parse("2026-01-01"), LocalDate.parse("2027-01-01"), "국민은행",
				new AccountNumber("1234567890"), new PersonName("김임대"), null);
		lease = leaseService.register(a.landlordId(), new LeaseRegistration(unit, new PersonName("홍길동"),
				PhoneNumber.parse("01011112222"), null, 500_000, 0, terms, false));
	}

	@Test
	void newLinkIsShownOnceAndOnlyItsHashIsStored(CapturedOutput output) throws Exception {
		assertThat(page("/app/leases/" + lease)).contains("아직 세입자 링크가 없어요").contains("세입자 링크 만들기");

		MvcResult result = issue("");
		String raw = rawToken(result);

		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
		assertThat(result.getResponse().getContentAsString()).contains("이 링크는 <strong>지금만 보여요.</strong>")
				.contains("2027년 4월 1일</span>까지 열 수 있어요");
		assertThat(result.getFlashMap().isEmpty()).as("원문을 flash에 넣지 않는다").isTrue();
		assertThat(jdbc.queryForObject("select token_hash from tenant_access_token where revoked_at is null",
				String.class)).isEqualTo(SecureTokens.sha256Hex(raw));
		assertThat(jdbc.queryForObject("select string_agg(t::text, '|') from tenant_access_token t", String.class))
				.doesNotContain(raw);
		assertThat(jdbc.queryForObject("select count(*) from spring_session_attributes "
				+ "where position(convert_to(?, 'UTF8') in attribute_bytes) > 0", Integer.class, raw))
				.as("원문이 세션(DB)에 없다").isZero();
		assertThat(output.getAll()).as("원문이 로그에 없다").doesNotContain(raw);

		String detail = page("/app/leases/" + lease);
		assertThat(detail).contains("링크가 열려 있어요").contains("아직 열어 보지 않았어요").contains("2027년 4월 1일")
				.contains("링크 주소는 만들 때만 보여요").doesNotContain(raw);
	}

	@Test
	void resubmittingTheFirstIssueDoesNotCreateAnotherLink() throws Exception {
		issue("");
		long first = openLinkId();

		MvcResult refresh = issue("");

		assertThat(refresh.getResponse().getRedirectedUrl()).isEqualTo("/app/leases/" + lease + "#tenant-link");
		assertThat(refresh.getFlashMap().get("tenantLinkAlert")).isEqualTo(STALE_ISSUE);
		assertThat(openLinkId()).as("방금 만든 링크가 그대로 열려 있다").isEqualTo(first);
		assertThat(jdbc.queryForObject("select count(*) from tenant_access_token", Integer.class)).isEqualTo(1);
	}

	@Test
	void reissueClosesTheOldLinkAndResubmittingItKeepsTheNewOneOpen() throws Exception {
		String firstRaw = rawToken(issue(""));
		long first = openLinkId();
		assertThat(page("/app/leases/" + lease + "/tenant-link/reissue")).contains("예전에 보낸 링크는 더 이상 열리지 않아요")
				.contains("name=\"openLinkId\" value=\"" + first + "\"");

		String secondRaw = rawToken(issue(String.valueOf(first)));
		long second = openLinkId();
		assertThat(second).isNotEqualTo(first);
		assertThat(secondRaw).isNotEqualTo(firstRaw);
		assertThat(jdbc.queryForObject("select revoked_at is not null from tenant_access_token where id = ?",
				Boolean.class, first)).isTrue();

		// [새 링크 만들기] 응답 화면에서 새로고침 → 같은 POST(예전 ID)가 다시 온다
		MvcResult refresh = issue(String.valueOf(first));
		assertThat(refresh.getFlashMap().get("tenantLinkAlert")).isEqualTo(STALE_ISSUE);
		assertThat(openLinkId()).as("새로고침해도 새 링크가 닫히지 않는다").isEqualTo(second);
		assertThat(jdbc.queryForObject("select count(*) from tenant_access_token", Integer.class)).isEqualTo(2);
	}

	@Test
	void closingTheLinkAndClosingAgain() throws Exception {
		issue("");
		long open = openLinkId();
		assertThat(page("/app/leases/" + lease + "/tenant-link/close")).contains("세입자 링크를 닫을까요?")
				.contains("닫은 링크는 다시 열 수 없어요");

		MvcResult closed = close(String.valueOf(open));
		assertThat(closed.getFlashMap().get("notice"))
				.isEqualTo("세입자 링크를 닫았어요. 세입자는 예전 링크로 더 이상 들어올 수 없어요. 세입자 안내 문자도 멈췄어요.");
		assertThat(
				jdbc.queryForObject("select count(*) from tenant_access_token where revoked_at is null", Integer.class))
				.isZero();
		assertThat(page("/app/leases/" + lease)).contains("아직 세입자 링크가 없어요");

		assertThat(close(String.valueOf(open)).getFlashMap().get("tenantLinkAlert"))
				.isEqualTo("링크가 이미 바뀌었거나 닫혔어요. 아래 최신 상태를 확인해 주세요.");
	}

	@Test
	void linkCanBeIssuedUntilNinetyDaysAfterTheActualEnd() throws Exception {
		clock.set(Instant.parse("2027-04-01T14:59:59Z"));
		assertThat(issue("").getResponse().getStatus()).as("서울 2027-04-01 23:59:59").isEqualTo(200);

		clock.set(Instant.parse("2027-04-01T15:00:00Z"));
		long open = openLinkId();
		MvcResult expired = issue(String.valueOf(open));
		assertThat(expired.getFlashMap().get("tenantLinkAlert")).isEqualTo("계약이 끝나고 90일이 지나 세입자 링크를 만들 수 없어요.");
		assertThat(page("/app/leases/" + lease)).contains("링크를 쓸 수 있는 기간이 지나 세입자가 열 수 없어요").doesNotContain("새 링크 만들기")
				.contains("링크 닫기");
	}

	@Test
	void endedLeaseUsesTheEndedDay() throws Exception {
		jdbc.update("update lease set status = 'ENDED', ended_on = '2026-10-10' where id = ?", lease);

		MvcResult result = issue("");

		assertThat(result.getResponse().getContentAsString()).contains("2027년 1월 8일</span>까지 열 수 있어요");
	}

	@Test
	void concurrentIssuesLeaveExactlyOneOpenLink() throws Exception {
		int threads = 4;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<Boolean>> results = new ArrayList<>();
		for (int i = 0; i < threads; i++) {
			results.add(pool.submit(() -> {
				start.await();
				try {
					tenantLinkService.issue(a.landlordId(), lease, null);
					return true;
				} catch (TenantLinkStaleException e) {
					return false;
				}
			}));
		}
		start.countDown();
		int issued = 0;
		for (Future<Boolean> result : results) {
			issued += result.get() ? 1 : 0;
		}
		pool.shutdown();

		assertThat(issued).isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from tenant_access_token", Integer.class)).isEqualTo(1);
	}

	@Test
	void deletingTheLeaseDeletesItsLinks() throws Exception {
		issue("");

		mockMvc.perform(post("/app/leases/" + lease + "/delete").with(csrf()).cookie(a.cookie()));

		assertThat(jdbc.queryForObject("select count(*) from lease", Integer.class)).isZero();
		assertThat(jdbc.queryForObject("select count(*) from tenant_access_token", Integer.class)).isZero();
	}

	private MvcResult issue(String openLinkId) throws Exception {
		return mockMvc.perform(post("/app/leases/" + lease + "/tenant-link").with(csrf()).cookie(a.cookie())
				.param("openLinkId", openLinkId)).andReturn();
	}

	private MvcResult close(String openLinkId) throws Exception {
		return mockMvc.perform(post("/app/leases/" + lease + "/tenant-link/close").with(csrf()).cookie(a.cookie())
				.param("openLinkId", openLinkId)).andReturn();
	}

	private static String rawToken(MvcResult result) throws Exception {
		Matcher matcher = LINK.matcher(result.getResponse().getContentAsString());
		assertThat(matcher.find()).as("응답 화면에 링크가 있다").isTrue();
		return matcher.group(1);
	}

	private long openLinkId() {
		return jdbc.queryForObject("select id from tenant_access_token where revoked_at is null", Long.class);
	}

	private String page(String path) throws Exception {
		return mockMvc.perform(get(path).cookie(a.cookie())).andReturn().getResponse().getContentAsString()
				.replaceAll("\\s+", " ");
	}

}
