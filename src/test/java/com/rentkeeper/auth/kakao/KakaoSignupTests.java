package com.rentkeeper.auth.kakao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import com.rentkeeper.support.FakeKakaoServer;
import com.rentkeeper.support.KakaoIntegrationTest;
import com.rentkeeper.support.RecordingEmailSender;
import com.rentkeeper.support.TestData;

import jakarta.servlet.http.Cookie;

/**
 * 카카오 신규 가입 단계 (/signup/kakao, ADR-014)와 카카오 계정의 알림 받을 이메일 (M1-c 결정 5·6·7·11).
 */
class KakaoSignupTests extends KakaoIntegrationTest {

	private static final String KAKAO_ID = "2000000001";

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	RecordingEmailSender mail;

	@BeforeEach
	void setUp() {
		TestData.reset(jdbcTemplate);
		kakao.reset();
		mail.clear();
	}

	@Test
	void signupFormIsPrefilledWithNicknameOnly() throws Exception {
		// 이메일 scope를 요청하지 않으므로, 응답에 이메일이 있더라도 쓰지 않는다 (ADR-014)
		Cookie pending = pendingSignup(FakeKakaoServer.user(KAKAO_ID, "kim@example.com", true, true, "김카카오"));

		mockMvc.perform(get("/signup/kakao").cookie(pending)).andExpect(status().isOk())
				.andExpect(content().string(containsString("value=\"김카카오\"")))
				.andExpect(content().string(not(containsString("kim@example.com"))))
				.andExpect(content().string(containsString("가입 그만두기")));
	}

	@Test
	void pendingSignupSessionPrincipalNameDoesNotContainKakaoId() throws Exception {
		pendingSignup(FakeKakaoServer.user(KAKAO_ID, null, false, false, "김카카오"));

		// 세션 속성(직렬화한 임시 로그인 정보)에는 가입 처리를 위해 회원번호가 남아 있다. 인덱스된 이름 컬럼에만 없다 (ADR-014)
		assertThat(jdbcTemplate.queryForList(
				"select principal_name from spring_session where principal_name is not null", String.class))
				.singleElement().asString().startsWith("kakao-signup:").doesNotContain(KAKAO_ID);
	}

	@Test
	void enteredEmailIsVerifiedByOurMailEvenIfKakaoSentVerifiedEmail() throws Exception {
		// 이메일 scope 없이는 "카카오가 인증한 이메일 → 바로 인증" 분기가 동작하지 않는다 (지금 기본 경로, ADR-014)
		Cookie pending = pendingSignup(FakeKakaoServer.user(KAKAO_ID, "kim@example.com", true, true, "김카카오"));

		MvcResult result = submit(pending, "김임대", "kim@example.com", true);

		assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/app");
		Map<String, Object> row = landlordRow();
		assertThat(row).containsEntry("auth_provider", "KAKAO").containsEntry("kakao_id", KAKAO_ID)
				.containsEntry("name", "김임대").containsEntry("email", "kim@example.com")
				.containsEntry("status", "ACTIVE").containsEntry("verified", false);
		assertThat(row.get("password_hash")).isNull();
		assertThat(consentTypes()).containsExactly("PRIVACY", "TERMS");
		assertThat(mail.lastTokenTo("kim@example.com")).as("입력한 주소는 우리 인증 메일로 확인").isNotBlank();

		// 정식 로그인으로 바뀌었다: 세션 id가 바뀌고, 새 세션으로 내 화면을 쓸 수 있고, 인증 안내 띠가 보인다
		Cookie session = result.getResponse().getCookie("SESSION");
		assertThat(session.getValue()).isNotEqualTo(pending.getValue());
		assertThat(jdbcTemplate.queryForList(
				"select principal_name from spring_session where principal_name is not null", String.class))
				.containsExactly(String.valueOf(row.get("id")));
		mockMvc.perform(get("/app").cookie(session)).andExpect(status().isOk())
				.andExpect(content().string(containsString("김임대")))
				.andExpect(content().string(containsString("이메일 인증이 아직")));
	}

	@Test
	void changedEmailIsUnverifiedAndGetsOurVerificationMail() throws Exception {
		Cookie pending = pendingSignup(FakeKakaoServer.user(KAKAO_ID, "kim@example.com", true, true, "김카카오"));

		Cookie session = submit(pending, "김임대", "other@example.com", true).getResponse().getCookie("SESSION");

		assertThat(landlordRow()).containsEntry("email", "other@example.com").containsEntry("verified", false);
		assertThat(mail.lastTokenTo("other@example.com")).isNotBlank();
		mockMvc.perform(get("/app").cookie(session)).andExpect(content().string(containsString("이메일 인증이 아직")))
				.andExpect(content().string(containsString("주소 고치기")));
	}

	@Test
	void blankEmailMeansLaterAndShowsMissingBanner() throws Exception {
		Cookie pending = pendingSignup(FakeKakaoServer.user(KAKAO_ID, null, false, false, null));

		mockMvc.perform(get("/signup/kakao").cookie(pending)).andExpect(status().isOk())
				.andExpect(content().string(containsString("id=\"name\"")));
		Cookie session = submit(pending, "김임대", "", true).getResponse().getCookie("SESSION");

		assertThat(landlordRow().get("email")).isNull();
		assertThat(mail.sent()).isEmpty();
		mockMvc.perform(get("/app").cookie(session)).andExpect(content().string(containsString("알림 받을 이메일이 아직 없어요")));
	}

	@Test
	void withoutAgreementNoAccountIsCreated() throws Exception {
		Cookie pending = pendingSignup(FakeKakaoServer.user(KAKAO_ID, null, false, false, "김카카오"));

		mockMvc.perform(post("/signup/kakao").with(csrf()).cookie(pending).param("name", "김임대")
				// 브라우저는 체크하지 않은 체크박스 대신 th:field가 넣은 _agreeTerms 표시만 보낸다
				.param("_agreeTerms", "on")).andExpect(status().isOk())
				.andExpect(content().string(containsString("동의해 주세요")));

		assertThat(landlordCount()).isZero();
	}

	@Test
	void invalidNameOrEmailIsRejected() throws Exception {
		Cookie pending = pendingSignup(FakeKakaoServer.user(KAKAO_ID, null, false, false, "김카카오"));

		mockMvc.perform(
				post("/signup/kakao").with(csrf()).cookie(pending).param("name", "  ").param("agreeTerms", "true"))
				.andExpect(content().string(containsString("이름(호칭)을 입력해 주세요")));
		mockMvc.perform(post("/signup/kakao").with(csrf()).cookie(pending).param("name", "김임대")
				.param("notificationEmail", "not-an-email").param("agreeTerms", "true"))
				.andExpect(content().string(containsString("이메일 주소를 다시 확인해 주세요")));

		assertThat(landlordCount()).isZero();
	}

	@Test
	void alreadyCompletedInAnotherWindowShowsMessageWithoutSecondAccount() throws Exception {
		Cookie pending = pendingSignup(FakeKakaoServer.user(KAKAO_ID, null, false, false, "김카카오"));
		// 다른 창에서 먼저 가입을 마쳤다
		jdbcTemplate.update("insert into landlord (auth_provider, kakao_id, name, created_at, updated_at) "
				+ "values ('KAKAO', ?, '먼저가입', now(), now())", KAKAO_ID);

		submitExpectingView(pending).andExpect(content().string(containsString("이미 가입을 마친 카카오 계정이에요")));

		assertThat(landlordCount()).isOne();
	}

	@Test
	void signupStepIsOnlyForPendingKakaoUsers() throws Exception {
		mockMvc.perform(get("/signup/kakao")).andExpect(redirectedUrl("/login"));

		Cookie pending = pendingSignup(FakeKakaoServer.user(KAKAO_ID, null, false, false, "김카카오"));
		Cookie session = submit(pending, "김임대", "", true).getResponse().getCookie("SESSION");
		mockMvc.perform(get("/signup/kakao").cookie(session)).andExpect(status().isForbidden());
	}

	@Test
	void kakaoAccountCanAddNotificationEmailAndVerifyIt() throws Exception {
		Cookie session = signedUpWithoutEmail();

		mockMvc.perform(get("/app/settings").cookie(session)).andExpect(content().string(containsString("카카오로 로그인해요")))
				.andExpect(content().string(not(containsString("비밀번호 바꾸기"))));
		mockMvc.perform(get("/app/settings/notification-email").cookie(session)).andExpect(status().isOk());
		mockMvc.perform(post("/app/settings/notification-email").with(csrf()).cookie(session).param("email", "bad"))
				.andExpect(status().isOk()).andExpect(content().string(containsString("이메일 주소를 다시 확인해 주세요")));
		mockMvc.perform(post("/app/settings/notification-email").with(csrf()).cookie(session).param("email",
				"first@example.com")).andExpect(redirectedUrl("/app/settings"));
		String firstToken = mail.lastTokenTo("first@example.com");

		// 주소를 잘못 넣어 고친다: 예전 주소의 링크는 더 이상 쓸 수 없다
		mockMvc.perform(post("/app/settings/notification-email").with(csrf()).cookie(session).param("email",
				"second@example.com")).andExpect(redirectedUrl("/app/settings"));
		mockMvc.perform(post("/verify-email").with(csrf()).param("token", firstToken))
				.andExpect(content().string(not(containsString("인증이 끝났어요"))));
		assertThat(landlordRow()).containsEntry("email", "second@example.com").containsEntry("verified", false);

		mockMvc.perform(post("/verify-email").with(csrf()).param("token", mail.lastTokenTo("second@example.com")));
		assertThat(landlordRow()).containsEntry("verified", true);

		// 인증된 주소를 바꾸는 기능은 P1이라 이 화면은 더 이상 열리지 않는다
		mockMvc.perform(get("/app/settings/notification-email").cookie(session)).andExpect(status().isNotFound());
		mockMvc.perform(post("/app/settings/notification-email").with(csrf()).cookie(session).param("email",
				"third@example.com")).andExpect(status().isNotFound());
	}

	@Test
	void notificationEmailIsRateLimitedLikeResend() throws Exception {
		Cookie session = signedUpWithoutEmail();

		for (int i = 1; i <= 3; i++) {
			mockMvc.perform(post("/app/settings/notification-email").with(csrf()).cookie(session).param("email",
					"n" + i + "@example.com")).andExpect(redirectedUrl("/app/settings"));
		}
		mockMvc.perform(
				post("/app/settings/notification-email").with(csrf()).cookie(session).param("email", "n4@example.com"))
				.andExpect(content().string(containsString("1시간에 3번까지")));
		assertThat(mail.sentTo("n4@example.com")).isEmpty();
	}

	@Test
	void passwordBasedPagesAreNotFoundForKakaoAccounts() throws Exception {
		Cookie session = signedUpWithoutEmail();

		mockMvc.perform(get("/app/settings/password").cookie(session)).andExpect(status().isNotFound());
		mockMvc.perform(post("/app/settings/password").with(csrf()).cookie(session).param("currentPassword", "x")
				.param("newPassword", "y").param("newPasswordConfirm", "y")).andExpect(status().isNotFound());
		mockMvc.perform(get("/app/settings/email").cookie(session)).andExpect(status().isNotFound());
		mockMvc.perform(post("/app/settings/email").with(csrf()).cookie(session).param("currentPassword", "x")
				.param("newEmail", "new@example.com")).andExpect(status().isNotFound());
	}

	private Cookie pendingSignup(String userJson) throws Exception {
		MvcResult callback = loginWithKakao(userJson);
		assertThat(callback.getResponse().getRedirectedUrl()).isEqualTo("/signup/kakao");
		return callback.getResponse().getCookie("SESSION");
	}

	private Cookie signedUpWithoutEmail() throws Exception {
		Cookie pending = pendingSignup(FakeKakaoServer.user(KAKAO_ID, null, false, false, "김카카오"));
		return submit(pending, "김임대", "", true).getResponse().getCookie("SESSION");
	}

	private MvcResult submit(Cookie pending, String name, String email, boolean agree) throws Exception {
		return mockMvc
				.perform(post("/signup/kakao").with(csrf()).cookie(pending).param("name", name)
						.param("notificationEmail", email).param("agreeTerms", String.valueOf(agree)))
				.andExpect(redirectedUrl("/app")).andReturn();
	}

	private ResultActions submitExpectingView(Cookie pending) throws Exception {
		return mockMvc.perform(post("/signup/kakao").with(csrf()).cookie(pending).param("name", "김임대")
				.param("notificationEmail", "").param("agreeTerms", "true")).andExpect(status().isOk());
	}

	private Map<String, Object> landlordRow() {
		return jdbcTemplate.queryForMap("select id, auth_provider, kakao_id, name, email, password_hash, status, "
				+ "email_verified_at is not null as verified from landlord");
	}

	private int landlordCount() {
		return jdbcTemplate.queryForObject("select count(*) from landlord", Integer.class);
	}

	private List<String> consentTypes() {
		return jdbcTemplate.queryForList("select document_type from consent_log order by document_type", String.class);
	}

}
