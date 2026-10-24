package com.rentkeeper.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
import com.rentkeeper.demo.application.DemoAccountPurger;
import com.rentkeeper.landlord.application.LandlordService;
import com.rentkeeper.support.LandlordLogin;
import com.rentkeeper.support.LandlordLogin.Session;
import com.rentkeeper.support.MutableClock;
import com.rentkeeper.support.MutableClockConfiguration;
import com.rentkeeper.support.TestData;

import jakarta.servlet.http.Cookie;

/**
 * 방문자별 임시 데모 계정 (M6 커밋 4, 결정 3과 보강 1~4, ADR-031). 시작 2026-10-17 09:00 서울. 이
 * 테스트는 동시 상한을 3으로 둔다.
 * <ul>
 * <li>가짜 데이터가 든 계정을 만들고 바로 로그인, 세입자 링크를 한 번 보여 주고, 모든 화면에 데모 띠</li>
 * <li>동시 상한과 IP당 한도</li>
 * <li>계정 설정 바꾸기·인증 메일·세입자 안내 문자 켜기를 막는다</li>
 * <li>24시간 뒤 지운다(세션 포함). 데모끼리도 서로의 데이터를 볼 수 없다</li>
 * </ul>
 */
@SpringBootTest(properties = {"app.demo.enabled=true", "app.demo.max-accounts=3"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, MutableClockConfiguration.class})
class DemoTests {

	private static final Pattern TENANT_LINK = Pattern.compile("http://localhost:8080/t/([A-Za-z0-9_-]{43})");

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	LandlordService landlordService;

	@Autowired
	PasswordEncoder passwordEncoder;

	@Autowired
	DemoAccountPurger purger;

	@Autowired
	MutableClock clock;

	int ip;

	@BeforeEach
	void setUp() {
		clock.set(MutableClockConfiguration.START);
		TestData.reset(jdbc);
	}

	@Test
	void startMakesASeededAccountShowsTheTenantLinkOnceAndABanner() throws Exception {
		assertThat(page(get("/"))).contains("데모로 둘러보기");

		MvcResult started = start("10.0.0.1");

		assertThat(started.getResponse().getStatus()).isEqualTo(200);
		assertThat(started.getResponse().getHeader("Cache-Control")).contains("no-store");
		String welcome = started.getResponse().getContentAsString();
		assertThat(welcome).contains("데모 계정을 만들었어요").contains("<span>10월 18일 09:00</span>에 모두 지워져요");
		Matcher link = TENANT_LINK.matcher(welcome);
		assertThat(link.find()).isTrue();
		assertThat(page(get("/t/" + link.group(1)))).contains("예시빌라 101호").contains("김*시");

		Cookie session = started.getResponse().getCookie("SESSION");
		String dashboard = page(get("/app/dashboard").cookie(session));
		assertThat(dashboard).contains("데모입니다. 실제 정보를 입력하지 마세요").contains("예시빌라").doesNotContain("이메일 인증이 아직 끝나지 않았어요");
		assertThat(jdbc.queryForList("select status from charge order by status", String.class)).contains("PAID",
				"TENANT_REPORTED", "OVERDUE");
		assertThat(jdbc.queryForObject("select count(*) from unit", Integer.class)).isEqualTo(6);
		assertThat(jdbc.queryForObject("select count(*) from lease", Integer.class)).isEqualTo(4);
		assertThat(jdbc.queryForObject("select tenant_sms from notification_preference", Boolean.class)).isFalse();
		assertThat(jdbc.queryForObject("select demo_expires_at from landlord", Timestamp.class).toInstant())
				.isEqualTo(MutableClockConfiguration.START.plus(Duration.ofHours(24)));
		assertThat(jdbc.queryForObject("select email_verified_at is null from landlord", Boolean.class)).isTrue();
	}

	@Test
	void demoCanMakeANewTenantLinkFromTheLeaseScreen() throws Exception {
		Cookie session = start("10.0.0.1").getResponse().getCookie("SESSION");
		long lease = jdbc.queryForObject(
				"select l.id from lease l join unit u on u.id = l.unit_id " + "where u.name = '201호'", Long.class);

		String issued = page(
				post("/app/leases/" + lease + "/tenant-link").with(csrf()).cookie(session).param("openLinkId", ""));

		assertThat(TENANT_LINK.matcher(issued).find()).isTrue();
	}

	@Test
	void atMostTheConfiguredNumberOfDemosAtOnce() throws Exception {
		for (int i = 1; i <= 3; i++) {
			assertThat(start("10.0.1." + i).getResponse().getStatus()).isEqualTo(200);
		}

		MvcResult full = start("10.0.1.4");

		assertThat(full.getResponse().getStatus()).isEqualTo(503);
		assertThat(full.getResponse().getContentAsString()).contains("지금은 데모가 꽉 찼어요");
		assertThat(demoCount()).isEqualTo(3);
		clock.advance(Duration.ofHours(24));
		assertThat(start("10.0.1.4").getResponse().getStatus()).as("만료된 데모는 세지 않는다").isEqualTo(200);
	}

	@Test
	void fiveStartsPerIpPerHour() throws Exception {
		for (int i = 0; i < 5; i++) {
			start("10.0.2.1");
		}

		assertThat(start("10.0.2.1").getResponse().getStatus()).isEqualTo(429);
		assertThat(start("10.0.2.2").getResponse().getStatus()).as("상한 3개는 이미 찼다").isEqualTo(503);
	}

	@Test
	void demoCannotChangeAccountSettingsOrTriggerMail() throws Exception {
		Cookie session = start("10.0.0.1").getResponse().getCookie("SESSION");
		String hashBefore = jdbc.queryForObject("select password_hash from landlord", String.class);
		String emailBefore = jdbc.queryForObject("select email from landlord", String.class);

		List<MockHttpServletRequestBuilder> blocked = List.of(get("/app/settings/password"),
				post("/app/settings/password").param("currentPassword", "x").param("newPassword", "Haengbok-Villa-2027")
						.param("newPasswordConfirm", "Haengbok-Villa-2027"),
				get("/app/settings/email"), post("/app/settings/email").param("email", "real@example.com"),
				get("/app/settings/notification-email"),
				post("/app/settings/notification-email").param("email", "real@example.com"),
				get("/app/settings/withdraw"), post("/app/settings/withdraw"), post("/app/email-verification/resend"));
		for (MockHttpServletRequestBuilder request : blocked) {
			MvcResult result = mockMvc.perform(request.with(csrf()).cookie(session)).andReturn();
			assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/app/settings");
			assertThat(result.getFlashMap().get("notice")).isEqualTo("데모 계정에서는 계정 설정을 바꿀 수 없어요. 가입하면 쓸 수 있어요.");
		}

		assertThat(jdbc.queryForObject("select password_hash from landlord", String.class)).isEqualTo(hashBefore);
		assertThat(jdbc.queryForObject("select email from landlord", String.class)).isEqualTo(emailBefore)
				.endsWith("@demo.invalid");
		assertThat(jdbc.queryForObject("select status from landlord", String.class)).isEqualTo("ACTIVE");
		assertThat(jdbc.queryForObject("select count(*) from account_token", Integer.class)).as("메일 토큰 없음").isZero();
	}

	@Test
	void ordinaryAccountsAreNotRestricted() throws Exception {
		Session a = LandlordLogin.signUpAndLogin(mockMvc, landlordService, passwordEncoder, "a@example.com");

		assertThat(
				mockMvc.perform(get("/app/settings/password").cookie(a.cookie())).andReturn().getResponse().getStatus())
				.isEqualTo(200);
		assertThat(page(get("/app/dashboard").cookie(a.cookie()))).doesNotContain("데모입니다");
	}

	@Test
	void demoCannotTurnOnTenantTexts() throws Exception {
		Cookie session = start("10.0.0.1").getResponse().getCookie("SESSION");
		assertThat(page(get("/app/settings/notifications").cookie(session))).contains("데모 계정에서는 켤 수 없어요");

		MvcResult saved = mockMvc.perform(post("/app/settings/notifications").with(csrf()).cookie(session)
				.param("tenantReport", "true").param("tenantSms", "true")).andReturn();

		assertThat(saved.getFlashMap().get("notice")).isEqualTo("알림 설정을 저장했어요. 데모 계정에서는 세입자 안내 문자를 켤 수 없어요.");
		assertThat(jdbc.queryForObject("select tenant_sms from notification_preference", Boolean.class)).isFalse();
	}

	@Test
	void expiredDemosAreDeletedWithTheirSessions() throws Exception {
		Cookie session = start("10.0.0.1").getResponse().getCookie("SESSION");
		Session a = LandlordLogin.signUpAndLogin(mockMvc, landlordService, passwordEncoder, "a@example.com");
		clock.advance(Duration.ofHours(23));
		assertThat(purger.run()).as("아직 지울 때가 아님").isZero();

		clock.advance(Duration.ofHours(1));

		assertThat(purger.run()).isEqualTo(1);
		assertThat(demoCount()).isZero();
		assertThat(jdbc.queryForObject("select count(*) from lease", Integer.class)).isZero();
		assertThat(jdbc.queryForObject("select count(*) from landlord where id = ?", Integer.class, a.landlordId()))
				.as("일반 계정은 그대로").isEqualTo(1);
		assertThat(mockMvc.perform(get("/app/dashboard").cookie(session)).andReturn().getResponse().getRedirectedUrl())
				.as("데모 세션도 지워졌다").contains("/login");
	}

	@Test
	void demosCannotSeeEachOther() throws Exception {
		start("10.0.0.1");
		long firstLease = jdbc.queryForObject("select min(id) from lease", Long.class);
		Cookie second = start("10.0.0.2").getResponse().getCookie("SESSION");

		assertThat(
				mockMvc.perform(get("/app/leases/" + firstLease).cookie(second)).andReturn().getResponse().getStatus())
				.isEqualTo(404);
	}

	@Test
	void loggedInLandlordsDoNotGetANewDemo() throws Exception {
		Session a = LandlordLogin.signUpAndLogin(mockMvc, landlordService, passwordEncoder, "a@example.com");

		MvcResult result = mockMvc.perform(post("/demo").with(csrf()).cookie(a.cookie())).andReturn();

		assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/app");
		assertThat(demoCount()).isZero();
	}

	private MvcResult start(String address) throws Exception {
		return mockMvc.perform(post("/demo").with(csrf()).with(request -> {
			request.setRemoteAddr(address);
			return request;
		})).andReturn();
	}

	private String page(MockHttpServletRequestBuilder request) throws Exception {
		return mockMvc.perform(request).andReturn().getResponse().getContentAsString().replaceAll("\\s+", " ");
	}

	private int demoCount() {
		return jdbc.queryForObject("select count(*) from landlord where demo_expires_at is not null", Integer.class);
	}

}
