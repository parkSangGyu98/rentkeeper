package com.rentkeeper.tenantlink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.rentkeeper.TestcontainersConfiguration;
import com.rentkeeper.landlord.application.LandlordService;
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
 * 세입자 안내 문자 그만 받기·다시 받기 (TEN-6, M5 결정 9). 수신 거부는 링크가 아니라 세입자에 저장한다: 새 링크로 바뀌어도,
 * 같은 세입자의 다른 계약에도 그대로다. 확인 화면 → POST, 멱등, Origin 검사, 링크당 1시간 10회.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, MutableClockConfiguration.class})
class TenantOptOutTests {

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

	long property;

	long lease;

	String token;

	@BeforeEach
	void setUp() throws Exception {
		clock.set(MutableClockConfiguration.START);
		TestData.reset(jdbc);
		a = LandlordLogin.signUpAndLogin(mockMvc, landlordService, passwordEncoder, "a@example.com");
		property = jdbc.queryForObject("insert into property (landlord_id, name, created_at, updated_at) "
				+ "values (?, '행복빌라', now(), now()) returning id", Long.class, a.landlordId());
		lease = register("101호", false);
		token = tenantLinkService.issue(a.landlordId(), lease, null).rawToken();
	}

	@Test
	void optOutAndBackInThroughConfirmScreens() throws Exception {
		assertThat(page("/t/" + token)).contains("안내 문자 그만 받기").doesNotContain("안내 문자를 받지 않기로 하셨어요");
		assertThat(page("/t/" + token + "/optout")).contains("안내 문자를 그만 받을까요?").contains("네, 그만 받을게요");
		assertThat(optOutAt()).as("확인 화면만 열면 바뀌지 않는다").isNull();

		MvcResult done = mockMvc.perform(post("/t/" + token + "/optout")).andReturn();

		assertThat(done.getResponse().getStatus()).isEqualTo(200);
		assertThat(squash(done)).contains("안내 문자를 그만 받기로 했어요").contains("안내 문자 다시 받기");
		assertThat(optOutAt()).isEqualTo(MutableClockConfiguration.START);
		assertThat(page("/t/" + token)).contains("안내 문자를 받지 않기로 하셨어요").contains("안내 문자 다시 받기");
		assertThat(page("/t/" + token + "/optout")).as("이미 거부했으면 확인 대신 결과 화면").contains("안내 문자를 그만 받기로 했어요");
		assertThat(landlordLeasePage()).as("임대인은 보기만 한다").contains("세입자가 안내 문자를 받지 않기로 했어요");

		MvcResult back = mockMvc.perform(post("/t/" + token + "/optin")).andReturn();

		assertThat(squash(back)).contains("안내 문자를 다시 받기로 했어요");
		assertThat(optOutAt()).isNull();
		assertThat(landlordLeasePage()).doesNotContain("세입자가 안내 문자를 받지 않기로 했어요");
	}

	@Test
	void repeatingTheSamePostChangesNothing() throws Exception {
		mockMvc.perform(post("/t/" + token + "/optout"));
		clock.advance(Duration.ofHours(2));

		MvcResult again = mockMvc.perform(post("/t/" + token + "/optout")).andReturn();

		assertThat(squash(again)).contains("안내 문자를 그만 받기로 했어요");
		assertThat(optOutAt()).as("처음 거부한 시각을 그대로 둔다").isEqualTo(MutableClockConfiguration.START);
		mockMvc.perform(post("/t/" + token + "/optin"));
		assertThat(squash(mockMvc.perform(post("/t/" + token + "/optin")).andReturn())).contains("안내 문자를 다시 받기로 했어요");
		assertThat(optOutAt()).isNull();
	}

	@Test
	void optOutBelongsToTheTenantNotTheLink() throws Exception {
		mockMvc.perform(post("/t/" + token + "/optout"));
		long openLinkId = jdbc.queryForObject("select id from tenant_access_token where revoked_at is null",
				Long.class);

		String newToken = tenantLinkService.issue(a.landlordId(), lease, openLinkId).rawToken();
		long sameTenantLease = register("102호", true);
		String otherLeaseToken = tenantLinkService.issue(a.landlordId(), sameTenantLease, null).rawToken();

		assertThat(page("/t/" + newToken)).as("새 링크로 바뀌어도 그대로").contains("안내 문자를 받지 않기로 하셨어요");
		assertThat(page("/t/" + otherLeaseToken)).as("같은 세입자의 다른 계약도 그대로").contains("안내 문자를 받지 않기로 하셨어요");
		assertThat(
				jdbc.queryForObject("select count(*) from tenant where notify_opt_out_at is not null", Integer.class))
				.isEqualTo(1);
	}

	@Test
	void postFromAnotherSiteIsRefused() throws Exception {
		for (String origin : new String[]{"https://evil.example", "null"}) {
			MvcResult refused = mockMvc.perform(post("/t/" + token + "/optout").header("Origin", origin)).andReturn();
			assertThat(refused.getResponse().getStatus()).as(origin).isEqualTo(403);
			assertThat(squash(refused)).contains("다시 열어 주세요");
		}
		assertThat(optOutAt()).isNull();

		assertThat(mockMvc.perform(post("/t/" + token + "/optout").header("Origin", "http://localhost:8080"))
				.andReturn().getResponse().getStatus()).isEqualTo(200);
		assertThat(optOutAt()).isNotNull();
		assertThat(mockMvc.perform(post("/t/" + token + "/optin").header("Origin", "https://evil.example")).andReturn()
				.getResponse().getStatus()).isEqualTo(403);
		assertThat(optOutAt()).as("다시 받기도 막는다").isNotNull();
	}

	@Test
	void tenPostsPerLinkPerHour() throws Exception {
		for (int i = 0; i < 10; i++) {
			String path = "/t/" + token + (i % 2 == 0 ? "/optout" : "/optin");
			assertThat(mockMvc.perform(post(path)).andReturn().getResponse().getStatus()).isEqualTo(200);
		}

		MvcResult blocked = mockMvc.perform(post("/t/" + token + "/optout")).andReturn();

		assertThat(blocked.getResponse().getStatus()).isEqualTo(429);
		assertThat(squash(blocked)).contains("잠시 뒤에 열어 주세요");
		assertThat(optOutAt()).as("막힌 요청은 바꾸지 않는다").isNull();
		clock.advance(Duration.ofHours(1));
		assertThat(mockMvc.perform(post("/t/" + token + "/optout")).andReturn().getResponse().getStatus())
				.isEqualTo(200);
	}

	@Test
	void closedLinkCannotChangeTheChoice() throws Exception {
		long openLinkId = jdbc.queryForObject("select id from tenant_access_token where revoked_at is null",
				Long.class);
		tenantLinkService.close(a.landlordId(), lease, openLinkId);

		for (MockHttpServletRequestBuilder request : new MockHttpServletRequestBuilder[]{get("/t/" + token + "/optout"),
				post("/t/" + token + "/optout"), post("/t/" + token + "/optin")}) {
			assertThat(mockMvc.perform(request).andReturn().getResponse().getStatus()).isEqualTo(404);
		}
		assertThat(optOutAt()).isNull();
	}

	private long register(String unitName, boolean existingTenant) {
		long unit = jdbc.queryForObject(
				"insert into unit (landlord_id, property_id, name, type, created_at, updated_at) "
						+ "values (?, ?, ?, 'ONE_ROOM', now(), now()) returning id",
				Long.class, a.landlordId(), property, unitName);
		LeaseTerms terms = new LeaseTerms(0, 25, LocalDate.parse("2026-01-01"), LocalDate.parse("2027-01-01"), "국민은행",
				new AccountNumber("1234567890"), new PersonName("김임대"), null);
		return leaseService.register(a.landlordId(), new LeaseRegistration(unit, new PersonName("홍길동"),
				PhoneNumber.parse("01011112222"), null, 500_000, 0, terms, existingTenant));
	}

	private Instant optOutAt() {
		Timestamp at = jdbc.queryForObject(
				"select t.notify_opt_out_at from tenant t join lease l on l.tenant_id = t.id where l.id = ?",
				Timestamp.class, lease);
		return at == null ? null : at.toInstant();
	}

	private String landlordLeasePage() throws Exception {
		return squash(mockMvc.perform(get("/app/leases/" + lease).cookie(a.cookie())).andReturn());
	}

	private String page(String path) throws Exception {
		return squash(mockMvc.perform(get(path)).andReturn());
	}

	private static String squash(MvcResult result) throws Exception {
		return result.getResponse().getContentAsString().replaceAll("\\s+", " ");
	}

}
