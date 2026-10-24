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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.rentkeeper.TestcontainersConfiguration;
import com.rentkeeper.landlord.application.LandlordService;
import com.rentkeeper.landlord.domain.EmailAddress;
import com.rentkeeper.support.FakeKakaoServer;

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

	static final FakeKakaoServer kakao = FakeKakaoServer.start();

	/** 카카오 로그인 경로를 켜려고 가짜 카카오 서버를 쓴다 (KakaoIntegrationTest와 같은 값) */
	@DynamicPropertySource
	static void kakaoProperties(DynamicPropertyRegistry registry) {
		registry.add("app.kakao.client-id", () -> "test-client-id");
		registry.add("app.kakao.client-secret", () -> "test-client-secret");
		registry.add("app.kakao.admin-key", () -> "test-admin-key");
		registry.add("app.kakao.authorization-uri", () -> kakao.baseUrl() + "/oauth/authorize");
		registry.add("app.kakao.token-uri", () -> kakao.baseUrl() + "/oauth/token");
		registry.add("app.kakao.user-info-uri", () -> kakao.baseUrl() + "/v2/user/me");
		registry.add("app.kakao.unlink-uri", () -> kakao.baseUrl() + "/v1/user/unlink");
	}

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

	// ---- 새 404 필터가 정상 경로를 404로 만들지 않는지 (필터는 권한 검사 앞에 있다)

	@Test
	void staticFilesAndHealthCheckAreServed() throws Exception {
		String session = login();
		for (String cookie : new String[]{null, session}) {
			HttpResponse<String> css = get("/css/app.css", cookie);
			assertThat(css.statusCode()).as("빌드한 CSS").isEqualTo(200);
			assertThat(css.headers().firstValue("Content-Type").orElseThrow()).startsWith("text/css");
			assertThat(get(FONT_CHUNK, cookie).statusCode()).as("글꼴 조각").isEqualTo(200);
			assertThat(get("/js/copy.js", cookie).statusCode()).isEqualTo(200);
			// 운영 헬스 체크 (Dockerfile HEALTHCHECK: curl -fsS
			// http://localhost:8080/actuator/health)
			HttpResponse<String> health = get("/actuator/health", cookie);
			assertThat(health.statusCode()).isEqualTo(200);
			assertThat(health.body()).contains("UP");
			// robots.txt: 정적 파일 체인이 내준다. 첫 화면·약관·처리방침만 열고 나머지는 막는다
			HttpResponse<String> robots = get("/robots.txt", cookie);
			assertThat(robots.statusCode()).isEqualTo(200);
			assertThat(robots.headers().firstValue("Content-Type").orElseThrow()).startsWith("text/plain");
			assertThat(robots.headers().allValues("Set-Cookie")).isEmpty();
			assertThat(robots.body().lines().filter(line -> !line.startsWith("#")).toList()).containsExactly(
					"User-agent: *", "Allow: /$", "Allow: /terms", "Allow: /privacy", "Allow: /css/", "Allow: /js/",
					"Disallow: /");
			// 저장소에 없는 파일은 없는 주소 (favicon·images는 정적 파일 체인)
			for (String missing : new String[]{"/favicon.ico", "/images/none.png"}) {
				assertThat(get(missing, cookie).statusCode()).as(missing).isEqualTo(404);
			}
		}
	}

	@Test
	void pathsHandledBySecurityFiltersStillWork() throws Exception {
		// 카카오 로그인 시작과 콜백 (OAuth2 필터가 처리, 컨트롤러 없음)
		HttpResponse<String> start = get("/oauth2/authorization/kakao", null);
		assertThat(start.statusCode()).isEqualTo(302);
		assertThat(start.headers().firstValue("Location").orElseThrow())
				.startsWith(kakao.baseUrl() + "/oauth/authorize");
		HttpResponse<String> callback = get("/login/oauth2/code/kakao?code=fake&state=forged",
				cookie(start, "SESSION"));
		assertThat(callback.statusCode()).isEqualTo(302);
		assertThat(callback.headers().firstValue("Location").orElseThrow()).endsWith("/login?kakaoError");

		// 로그인 POST (틀린 비밀번호)
		HttpResponse<String> wrong = postLogin("Wrong-Password-2026", false);
		assertThat(wrong.statusCode()).isEqualTo(302);
		assertThat(wrong.headers().firstValue("Location").orElseThrow()).endsWith("/login?error");

		// 로그인 유지(remember-me): 세션 없이 remember-me 쿠키만으로 들어간다
		HttpResponse<String> remembered = postLogin(PASSWORD, true);
		assertThat(get("/app/dashboard", cookie(remembered, "remember-me")).statusCode()).isEqualTo(200);

		// 로그아웃 POST (LogoutFilter가 처리): CSRF 토큰이 없으면 403, 있으면 로그인 화면으로
		String session = login();
		String csrf = "_csrf=" + URLEncoder.encode(csrfToken(session), StandardCharsets.UTF_8);
		assertThat(send("POST", "/logout", session, "").statusCode()).isEqualTo(403);
		HttpResponse<String> logout = send("POST", "/logout", session, csrf);
		assertThat(logout.statusCode()).isEqualTo(302);
		assertThat(logout.headers().firstValue("Location").orElseThrow()).endsWith("/login?logout");
		assertThat(get("/app/dashboard", session).statusCode()).as("로그아웃한 세션").isEqualTo(302);
	}

	@Test
	void otherMethodOnMappedPathKeepsPreviousAnswer() throws Exception {
		// 컨트롤러가 있는 주소는 모두 허용 목록 안이라 필터가 손대지 않는다: CSRF가 없으면 403, 있으면 405
		assertThat(send("POST", "/terms", null, "").statusCode()).isEqualTo(403);
		String session = login();
		String csrf = "_csrf=" + URLEncoder.encode(csrfToken(session), StandardCharsets.UTF_8);
		assertThat(send("POST", "/terms", session, csrf).statusCode()).isEqualTo(405);
		assertThat(send("POST", "/app/dashboard", session, csrf).statusCode()).isEqualTo(405);
		assertThat(send("DELETE", "/app/dashboard", session, "").statusCode()).as("CSRF 없는 DELETE").isEqualTo(403);
	}

	@Test
	void headAnswersLikeGet() throws Exception {
		String session = login();
		String[][] cases = {{"/", null}, {"/login", null}, {"/css/app.css", null}, {"/actuator/health", null},
				{"/no-such-page", null}, {"/no-such-page", session}, {"/app/no-such-page", null},
				{"/app/no-such-page", session}, {"/app/dashboard", session}, {"/signup/kakao", session},
				{"/t/abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG", null}};
		for (String[] c : cases) {
			HttpResponse<String> head = send("HEAD", c[0], c[1], null);
			assertThat(head.statusCode()).as("HEAD " + c[0] + (c[1] == null ? "" : " (로그인)"))
					.isEqualTo(get(c[0], c[1]).statusCode());
			assertThat(head.body()).isEmpty();
		}
	}

	private static final String FONT_CHUNK = "/css/fonts/pretendard/woff2-dynamic-subset/PretendardVariable.subset.0.woff2";

	private HttpResponse<String> get(String path, String cookie) throws Exception {
		return send("GET", path, cookie, null);
	}

	/** form이 null이면 본문 없이 보낸다 */
	private HttpResponse<String> send(String method, String path, String cookie, String form) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
				// 브라우저처럼 HTML을 달라고 한다 (없으면 오류 응답이 JSON으로 온다). 헬스 체크는 운영 curl처럼 아무 형식이나
				.header("Accept", path.startsWith("/actuator") ? "*/*" : "text/html");
		if (cookie != null) {
			// Secure 쿠키는 Java HttpClient가 http로 다시 보내지 않으므로 Cookie 헤더를 직접 붙인다
			request.header("Cookie", cookie);
		}
		if (form == null) {
			request.method(method, HttpRequest.BodyPublishers.noBody());
		} else {
			request.header("Content-Type", "application/x-www-form-urlencoded").method(method,
					HttpRequest.BodyPublishers.ofString(form));
		}
		return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
	}

	/** 로그인하고 SESSION 쿠키("SESSION=…")를 돌려준다 */
	private String login() throws Exception {
		HttpResponse<String> login = postLogin(PASSWORD, false);
		assertThat(login.statusCode()).isEqualTo(302);
		assertThat(login.headers().firstValue("Location").orElseThrow()).endsWith("/app");
		return cookie(login, "SESSION");
	}

	private HttpResponse<String> postLogin(String password, boolean rememberMe) throws Exception {
		HttpResponse<String> loginPage = get("/login", null);
		Matcher csrf = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"").matcher(loginPage.body());
		assertThat(csrf.find()).isTrue();
		String form = "email=" + URLEncoder.encode(EMAIL, StandardCharsets.UTF_8) + "&password=" + password
				+ (rememberMe ? "&remember-me=on" : "") + "&_csrf="
				+ URLEncoder.encode(csrf.group(1), StandardCharsets.UTF_8);
		return send("POST", "/login", cookie(loginPage, "SESSION"), form);
	}

	/** 로그인한 화면의 로그아웃 폼에 든 CSRF 토큰 */
	private String csrfToken(String session) throws Exception {
		Matcher csrf = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"")
				.matcher(get("/app/dashboard", session).body());
		assertThat(csrf.find()).isTrue();
		return csrf.group(1);
	}

	/** 응답이 내려준 쿠키 하나 ("이름=값") */
	private static String cookie(HttpResponse<String> response, String name) {
		return response.headers().allValues("Set-Cookie").stream().filter(value -> value.startsWith(name + "="))
				.findFirst().map(value -> value.split(";")[0]).orElseThrow();
	}

}
