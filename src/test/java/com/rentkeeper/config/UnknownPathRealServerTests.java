package com.rentkeeper.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.rentkeeper.TestcontainersConfiguration;
import com.rentkeeper.landlord.application.LandlordService;
import com.rentkeeper.landlord.domain.EmailAddress;

/**
 * 없는 주소·권한 없는 주소의 상태 코드와 화면을 실제 내장 Tomcat에서 고정한다. MockMvc는 오류 화면 전달(/error)을 하지
 * 않아 화면 글자를 볼 수 없다.
 * <ul>
 * <li>허용 목록 밖의 없는 주소: 로그인 전·후 모두 404 "페이지를 찾을 수 없어요"</li>
 * <li>/app/ 아래: 비로그인은 로그인 화면으로, 로그인 뒤 없는 주소는 404</li>
 * <li>컨트롤러가 있지만 권한이 없는 주소: 지금처럼 403 "요청을 처리할 수 없어요"</li>
 * <li>세입자 링크(/t/**): 틀린 토큰·없는 하위 주소 모두 404, 세션 쿠키를 만들지 않고 noindex·no-store
 * 유지</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class UnknownPathRealServerTests {

	private static final String NOT_FOUND_TITLE = "페이지를 찾을 수 없어요";

	private static final String FORBIDDEN_TITLE = "요청을 처리할 수 없어요";

	private static final String EMAIL = "unknown-path@example.com";

	private static final String PASSWORD = "Haengbok-Villa-2026";

	@LocalServerPort
	int port;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	LandlordService landlordService;

	@Autowired
	PasswordEncoder passwordEncoder;

	final HttpClient client = HttpClient.newHttpClient();

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("delete from rate_limit_bucket");
		jdbcTemplate.update("delete from landlord where email = ?", EMAIL);
		landlordService.registerLocal(EmailAddress.of(EMAIL), passwordEncoder.encode(PASSWORD), "없는주소");
	}

	@Test
	void unknownPathOutsideAllowListIsNotFoundBeforeAndAfterLogin() throws Exception {
		HttpResponse<String> anonymous = get("/no-such-page", null);
		assertThat(anonymous.statusCode()).isEqualTo(404);
		assertThat(anonymous.body()).contains(NOT_FOUND_TITLE);

		String session = login();
		HttpResponse<String> landlord = get("/no-such-page", session);
		assertThat(landlord.statusCode()).isEqualTo(404);
		assertThat(landlord.body()).contains(NOT_FOUND_TITLE).doesNotContain(FORBIDDEN_TITLE);
		assertThat(get("/no/such/deep/page", session).statusCode()).isEqualTo(404);
		assertThat(get("/actuator/env", session).statusCode()).as("웹에 열지 않은 actuator도 없는 주소").isEqualTo(404);
	}

	@Test
	void appPathsKeepLoginRedirectAndNotFound() throws Exception {
		HttpResponse<String> anonymous = get("/app/no-such-page", null);
		assertThat(anonymous.statusCode()).as("/app/ 아래는 비로그인이면 로그인 화면으로").isEqualTo(302);
		assertThat(anonymous.headers().firstValue("Location").orElseThrow()).endsWith("/login");
		assertThat(get("/app/dashboard", null).statusCode()).isEqualTo(302);

		String session = login();
		HttpResponse<String> landlord = get("/app/no-such-page", session);
		assertThat(landlord.statusCode()).isEqualTo(404);
		assertThat(landlord.body()).contains(NOT_FOUND_TITLE);
		assertThat(get("/app/properties/999999999", session).statusCode()).isEqualTo(404);
	}

	@Test
	void mappedPathWithoutPermissionIsStillForbidden() throws Exception {
		String session = login();
		// 카카오 가입 단계 화면은 컨트롤러가 있지만 임대인 권한으로는 쓸 수 없다
		HttpResponse<String> response = get("/signup/kakao", session);
		assertThat(response.statusCode()).isEqualTo(403);
		assertThat(response.body()).contains(FORBIDDEN_TITLE);
	}

	@Test
	void tenantLinkPathsStayStatelessNotFound() throws Exception {
		String session = login();
		for (String path : new String[]{"/t/abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG",
				"/t/abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG/no-such-page", "/t/short"}) {
			for (String cookie : new String[]{null, session}) {
				HttpResponse<String> response = get(path, cookie);
				assertThat(response.statusCode()).as(path).isEqualTo(404);
				assertThat(response.headers().allValues("Set-Cookie")).as(path + " 세션 쿠키 없음").isEmpty();
				assertThat(response.headers().firstValue("X-Robots-Tag")).as(path).hasValue(SecurityConfig.ROBOTS);
				assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).as(path).contains("no-store");
			}
		}
	}

	private HttpResponse<String> get(String path, String cookie) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
				// 브라우저처럼 HTML을 달라고 한다 (없으면 오류 응답이 JSON으로 온다)
				.header("Accept", "text/html");
		if (cookie != null) {
			request.header("Cookie", cookie);
		}
		return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
	}

	/** 로그인하고 SESSION 쿠키("SESSION=…")를 돌려준다 */
	private String login() throws Exception {
		HttpResponse<String> loginPage = get("/login", null);
		String sessionCookie = loginPage.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0];
		Matcher csrf = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"").matcher(loginPage.body());
		assertThat(csrf.find()).isTrue();
		// Secure 쿠키는 Java HttpClient가 http로 다시 보내지 않으므로 Cookie 헤더를 직접 붙인다
		String form = "email=" + URLEncoder.encode(EMAIL, StandardCharsets.UTF_8) + "&password=" + PASSWORD + "&_csrf="
				+ URLEncoder.encode(csrf.group(1), StandardCharsets.UTF_8);
		HttpResponse<String> login = client
				.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/login"))
						.header("Cookie", sessionCookie).header("Content-Type", "application/x-www-form-urlencoded")
						.POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
		assertThat(login.statusCode()).isEqualTo(302);
		assertThat(login.headers().firstValue("Location").orElseThrow()).endsWith("/app");
		return login.headers().allValues("Set-Cookie").stream().filter(value -> value.startsWith("SESSION="))
				.findFirst().map(value -> value.split(";")[0]).orElse(sessionCookie);
	}

}
