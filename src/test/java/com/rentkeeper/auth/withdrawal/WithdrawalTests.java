package com.rentkeeper.auth.withdrawal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import com.rentkeeper.auth.rememberme.HashingRememberMeServices;
import com.rentkeeper.auth.token.AccountTokenService;
import com.rentkeeper.auth.token.TokenPurpose;
import com.rentkeeper.landlord.application.LandlordService;
import com.rentkeeper.landlord.domain.EmailAddress;
import com.rentkeeper.support.FakeKakaoServer;
import com.rentkeeper.support.FakeKakaoServer.Request;
import com.rentkeeper.support.KakaoIntegrationTest;
import com.rentkeeper.support.MutableClock;
import com.rentkeeper.support.MutableClockConfiguration;
import com.rentkeeper.support.RecordingEmailSender;
import com.rentkeeper.support.TestData;

import jakarta.servlet.http.Cookie;

/**
 * 회원 탈퇴 (AUTH-5, ADR-015): 재인증(이메일 계정은 비밀번호, 카카오 계정은 카카오 재로그인), 즉시 차단(모든 세션·로그인
 * 유지 삭제, 로그인 불가), 링크·실패 기록 삭제, 안내 메일, 카카오 연결 끊기와 그 기록.
 */
@ExtendWith(OutputCaptureExtension.class)
class WithdrawalTests extends KakaoIntegrationTest {

	private static final String EMAIL = "kim@example.com";

	private static final String PASSWORD = "Haengbok-Villa-2026";

	private static final String KAKAO_ID = "3000000001";

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	LandlordService landlordService;

	@Autowired
	PasswordEncoder passwordEncoder;

	@Autowired
	AccountTokenService tokenService;

	@Autowired
	RecordingEmailSender mail;

	@Autowired
	MutableClock clock;

	@BeforeEach
	void setUp() {
		clock.set(MutableClockConfiguration.START);
		TestData.reset(jdbcTemplate);
		jdbcTemplate.update("delete from persistent_logins");
		kakao.reset();
		mail.clear();
	}

	// ---------------------------------------------------------------- 이메일 가입 계정

	@Test
	void localWithdrawalNeedsConfirmAndCorrectPassword() throws Exception {
		long id = registerLocal(true);
		Cookie session = loginLocal();

		withdrawLocal(session, PASSWORD, false).andExpect(content().string(containsString("확인란에 체크해 주세요")));
		withdrawLocal(session, "wrong-password", true)
				.andExpect(content().string(containsString("지금 쓰는 비밀번호가 맞지 않아요")));

		assertThat(statusOf(id)).isEqualTo("ACTIVE");
		assertThat(jdbcTemplate.queryForObject("select count(*) from login_failure where landlord_id = ?",
				Integer.class, id)).as("틀린 비밀번호는 로그인 실패 횟수에 더한다").isOne();
	}

	@Test
	void localWithdrawalBlocksImmediatelyEverywhere() throws Exception {
		long id = registerLocal(true);
		Cookie otherDevice = loginLocal();
		Cookie rememberMe = loginWithRememberMe();
		Cookie session = loginLocal();
		tokenService.issue(id, TokenPurpose.PASSWORD_RESET, null);
		jdbcTemplate.update(
				"insert into login_failure (landlord_id, failed_count, last_failed_at, created_at, updated_at) values (?, 1, now(), now(), now())",
				id);

		MvcResult result = withdrawLocal(session, PASSWORD, true).andExpect(redirectedUrl("/withdrawn")).andReturn();

		assertThat(jdbcTemplate.queryForMap("select status, withdrawn_at, kakao_unlinked_at from landlord where id = ?",
				id)).containsEntry("status", "WITHDRAWN")
				.containsEntry("withdrawn_at", Timestamp.from(MutableClockConfiguration.START))
				.containsEntry("kakao_unlinked_at", null);
		assertThat(count("spring_session where principal_name = '" + id + "'")).as("모든 기기의 세션").isZero();
		assertThat(count("persistent_logins")).as("로그인 유지 토큰").isZero();
		assertThat(count("account_token where landlord_id = " + id)).as("메일로 나간 링크").isZero();
		assertThat(count("login_failure where landlord_id = " + id)).isZero();
		assertThat(kakao.requests()).as("이메일 계정은 카카오를 부르지 않는다").isEmpty();
		assertThat(result.getResponse().getCookie(HashingRememberMeServices.COOKIE_NAME).getMaxAge()).isZero();

		// 어느 기기에서도 더는 쓸 수 없다
		for (Cookie cookie : new Cookie[]{session, otherDevice}) {
			mockMvc.perform(get("/app").cookie(cookie)).andExpect(redirectedUrl("/login"));
		}
		mockMvc.perform(get("/app").cookie(rememberMe)).andExpect(redirectedUrl("/login"));
		mockMvc.perform(post("/login").with(csrf()).param("email", EMAIL).param("password", PASSWORD))
				.andExpect(redirectedUrl("/login?error"));
		mockMvc.perform(get("/withdrawn")).andExpect(status().isOk())
				.andExpect(content().string(containsString("탈퇴 처리됐어요")));
	}

	@Test
	void noticeMailGoesOnlyToVerifiedEmail() throws Exception {
		registerLocal(true);
		withdrawLocal(loginLocal(), PASSWORD, true).andExpect(redirectedUrl("/withdrawn"));
		assertThat(mail.lastTo(EMAIL).subject()).contains("탈퇴 처리됐어요");

		TestData.reset(jdbcTemplate);
		mail.clear();
		registerLocal(false);
		withdrawLocal(loginLocal(), PASSWORD, true).andExpect(redirectedUrl("/withdrawn"));
		assertThat(mail.sent()).as("인증 전 주소로는 안내 메일을 보내지 않는다").isEmpty();
	}

	@Test
	void sameEmailCanSignUpAgainRightAfterWithdrawal() throws Exception {
		long oldId = registerLocal(true);
		withdrawLocal(loginLocal(), PASSWORD, true).andExpect(redirectedUrl("/withdrawn"));

		long newId = landlordService.registerLocal(EmailAddress.of(EMAIL), passwordEncoder.encode(PASSWORD), "새가입");

		assertThat(newId).isNotEqualTo(oldId);
		assertThat(statusOf(oldId)).isEqualTo("WITHDRAWN");
		loginLocal();
	}

	@Test
	void kakaoOnlyEndpointIsNotFoundForLocalAccount() throws Exception {
		registerLocal(true);
		mockMvc.perform(post("/app/settings/withdraw/kakao").with(csrf()).cookie(loginLocal()).param("confirm", "true"))
				.andExpect(status().isNotFound());
	}

	// ---------------------------------------------------------------- 카카오 계정

	@Test
	void kakaoWithdrawalReauthenticatesWithPromptLoginAndUnlinks() throws Exception {
		long id = insertKakaoLandlord(KAKAO_ID);
		Cookie session = loginKakao(KAKAO_ID);

		mockMvc.perform(get("/app/settings/withdraw").cookie(session))
				.andExpect(content().string(containsString("action=\"/app/settings/withdraw/kakao\"")));
		confirmKakao(session).andExpect(content().string(containsString("href=\"/oauth2/authorization/kakao\"")))
				.andExpect(content().string(not(containsString("다른 브라우저로 열기"))));

		MvcResult start = mockMvc.perform(get("/oauth2/authorization/kakao").cookie(session)).andReturn();
		assertThat(queryParam(start.getResponse().getRedirectedUrl(), "prompt")).as("카카오에 다시 로그인하게 한다")
				.isEqualTo("login");
		MvcResult callback = callback(start, session, KAKAO_ID);

		assertThat(callback.getResponse().getRedirectedUrl()).isEqualTo("/withdrawn");
		assertThat(statusOf(id)).isEqualTo("WITHDRAWN");
		Request unlink = kakao.requestsTo("/v1/user/unlink").get(0);
		assertThat(unlink.method()).isEqualTo("POST");
		assertThat(unlink.authorization()).isEqualTo("KakaoAK test-admin-key");
		assertThat(unlink.form()).containsEntry("target_id_type", "user_id").containsEntry("target_id", KAKAO_ID);
		assertThat(
				jdbcTemplate.queryForObject("select kakao_unlinked_at from landlord where id = ?", Timestamp.class, id))
				.isEqualTo(Timestamp.from(MutableClockConfiguration.START));
		assertThat(count("spring_session where principal_name = '" + id + "'")).isZero();
		mockMvc.perform(get("/app").cookie(session)).andExpect(redirectedUrl("/login"));
		// 같은 카카오 계정으로 다시 오면 새 가입 단계 (탈퇴 계정은 되살리지 않는다)
		assertThat(loginWithKakao(FakeKakaoServer.user(KAKAO_ID, null, false, false, "김카카오")).getResponse()
				.getRedirectedUrl()).isEqualTo("/signup/kakao");
	}

	@Test
	void ordinaryKakaoLoginHasNoPromptAndDoesNotWithdraw() throws Exception {
		long id = insertKakaoLandlord(KAKAO_ID);

		MvcResult start = startKakaoLogin();
		assertThat(queryParam(start.getResponse().getRedirectedUrl(), "prompt")).isNull();
		kakao.returnUser(FakeKakaoServer.user(KAKAO_ID, null, false, false, "김카카오"));
		assertThat(completeKakaoLogin(start).getResponse().getRedirectedUrl()).isEqualTo("/app");

		assertThat(statusOf(id)).isEqualTo("ACTIVE");
	}

	@Test
	void kakaoWithdrawalNeedsConfirm() throws Exception {
		long id = insertKakaoLandlord(KAKAO_ID);
		Cookie session = loginKakao(KAKAO_ID);

		mockMvc.perform(post("/app/settings/withdraw/kakao").with(csrf()).cookie(session))
				.andExpect(content().string(containsString("확인란에 체크해 주세요")));
		MvcResult start = mockMvc.perform(get("/oauth2/authorization/kakao").cookie(session)).andReturn();
		assertThat(queryParam(start.getResponse().getRedirectedUrl(), "prompt")).isNull();

		assertThat(statusOf(id)).isEqualTo("ACTIVE");
	}

	@Test
	void unlinkFailureKeepsWithdrawalAndLeavesUnlinkedAtNull() throws Exception {
		long id = insertKakaoLandlord(KAKAO_ID);
		Cookie session = loginKakao(KAKAO_ID);
		kakao.unlinkStatus(500);

		confirmKakao(session);
		MvcResult start = mockMvc.perform(get("/oauth2/authorization/kakao").cookie(session)).andReturn();
		assertThat(callback(start, session, KAKAO_ID).getResponse().getRedirectedUrl()).isEqualTo("/withdrawn");

		assertThat(statusOf(id)).isEqualTo("WITHDRAWN");
		assertThat(kakao.requestsTo("/v1/user/unlink")).hasSize(1);
		assertThat(
				jdbcTemplate.queryForObject("select kakao_unlinked_at from landlord where id = ?", Timestamp.class, id))
				.isNull();
		// 끊기가 남았다 = 대기 행이 있다 (파기 배치가 다시 시도한다)
		assertThat(jdbcTemplate.queryForMap(
				"select withdrawn_at, failure_count, last_failure_type from kakao_unlink_pending where kakao_id = ?",
				KAKAO_ID)).containsEntry("withdrawn_at", Timestamp.from(MutableClockConfiguration.START))
				.containsEntry("failure_count", 1).containsEntry("last_failure_type", "TRANSIENT");
	}

	@Test
	void unlinkSuccessLeavesNoPendingRow() throws Exception {
		insertKakaoLandlord(KAKAO_ID);
		Cookie session = loginKakao(KAKAO_ID);

		confirmKakao(session);
		MvcResult start = mockMvc.perform(get("/oauth2/authorization/kakao").cookie(session)).andReturn();
		callback(start, session, KAKAO_ID);

		assertThat(count("kakao_unlink_pending")).isZero();
	}

	@Test
	void configErrorAtWithdrawalAlertsImmediately(CapturedOutput output) throws Exception {
		insertKakaoLandlord(KAKAO_ID);
		Cookie session = loginKakao(KAKAO_ID);
		kakao.unlinkStatus(401);

		confirmKakao(session);
		MvcResult start = mockMvc.perform(get("/oauth2/authorization/kakao").cookie(session)).andReturn();
		callback(start, session, KAKAO_ID);

		assertThat(jdbcTemplate.queryForObject("select last_failure_type from kakao_unlink_pending", String.class))
				.isEqualTo("CONFIG");
		assertThat(output).contains("KAKAO_UNLINK_ALERT source=withdrawal config=1")
				.contains("kakao unlink failed: status=401 kakaoCode=-1").doesNotContain("test-admin-key");
	}

	@Test
	void differentKakaoAccountDoesNotWithdrawAndLogsOut() throws Exception {
		long id = insertKakaoLandlord(KAKAO_ID);
		Cookie session = loginKakao(KAKAO_ID);

		confirmKakao(session);
		MvcResult start = mockMvc.perform(get("/oauth2/authorization/kakao").cookie(session)).andReturn();
		MvcResult callback = callback(start, session, "3000000999");

		assertThat(callback.getResponse().getRedirectedUrl()).isEqualTo("/login?withdrawMismatch");
		assertThat(statusOf(id)).isEqualTo("ACTIVE");
		assertThat(kakao.requestsTo("/v1/user/unlink")).isEmpty();
		assertThat(count("spring_session where principal_name is not null")).as("다른 계정 로그인 상태로 남지 않는다").isZero();
	}

	@Test
	void expiredIntentDoesNotWithdraw() throws Exception {
		long id = insertKakaoLandlord(KAKAO_ID);
		Cookie session = loginKakao(KAKAO_ID);

		confirmKakao(session);
		MvcResult start = mockMvc.perform(get("/oauth2/authorization/kakao").cookie(session)).andReturn();
		clock.advance(KakaoWithdrawalIntent.VALIDITY.plus(Duration.ofSeconds(1)));
		MvcResult callback = callback(start, session, KAKAO_ID);

		assertThat(callback.getResponse().getRedirectedUrl()).isEqualTo("/app/settings/withdraw?expired");
		assertThat(statusOf(id)).isEqualTo("ACTIVE");
	}

	@Test
	void kakaoCancelReturnsToWithdrawPageStillLoggedIn() throws Exception {
		long id = insertKakaoLandlord(KAKAO_ID);
		Cookie session = loginKakao(KAKAO_ID);

		confirmKakao(session);
		MvcResult start = mockMvc.perform(get("/oauth2/authorization/kakao").cookie(session)).andReturn();
		String state = queryParam(start.getResponse().getRedirectedUrl(), "state");
		MvcResult cancel = mockMvc.perform(get("/login/oauth2/code/kakao").param("error", "access_denied")
				.param("state", state).cookie(sessionAfter(start, session))).andReturn();

		assertThat(cancel.getResponse().getRedirectedUrl()).isEqualTo("/app/settings/withdraw?kakaoError");
		assertThat(statusOf(id)).isEqualTo("ACTIVE");
		mockMvc.perform(get("/app/settings/withdraw?kakaoError").cookie(sessionAfter(start, session)))
				.andExpect(status().isOk()).andExpect(content().string(containsString("탈퇴는 처리되지 않았어요")));
	}

	@Test
	void kakaoTalkInAppBrowserGetsNotice() throws Exception {
		insertKakaoLandlord(KAKAO_ID);
		Cookie session = loginKakao(KAKAO_ID);

		mockMvc.perform(post("/app/settings/withdraw/kakao").with(csrf()).cookie(session).param("confirm", "true")
				.header("User-Agent", "Mozilla/5.0 (Linux; Android 14) KAKAOTALK 10.8.0"))
				.andExpect(content().string(containsString("다른 브라우저로 열기")));
	}

	@Test
	void passwordEndpointIsNotFoundForKakaoAccount() throws Exception {
		insertKakaoLandlord(KAKAO_ID);
		mockMvc.perform(post("/app/settings/withdraw").with(csrf()).cookie(loginKakao(KAKAO_ID))
				.param("currentPassword", "x").param("confirm", "true")).andExpect(status().isNotFound());
	}

	// ---------------------------------------------------------------- 스키마 (V10)

	@Test
	void unlinkedAtIsOnlyAllowedForWithdrawnKakaoAccounts() {
		long local = registerLocal(true);
		long kakaoActive = insertKakaoLandlord(KAKAO_ID);

		assertThatThrownBy(
				() -> jdbcTemplate.update("update landlord set kakao_unlinked_at = now() where id = ?", local))
				.isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_landlord_kakao_unlinked");
		assertThatThrownBy(
				() -> jdbcTemplate.update("update landlord set kakao_unlinked_at = now() where id = ?", kakaoActive))
				.isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_landlord_kakao_unlinked");
		jdbcTemplate.update("update landlord set status = 'WITHDRAWN', withdrawn_at = now(), kakao_unlinked_at = now() "
				+ "where id = ?", kakaoActive);
	}

	// ---------------------------------------------------------------- 도우미

	private long registerLocal(boolean verified) {
		long id = landlordService.registerLocal(EmailAddress.of(EMAIL), passwordEncoder.encode(PASSWORD), "김임대");
		if (verified) {
			jdbcTemplate.update("update landlord set email_verified_at = now() where id = ?", id);
		}
		return id;
	}

	private Cookie loginLocal() throws Exception {
		return mockMvc.perform(post("/login").with(csrf()).param("email", EMAIL).param("password", PASSWORD))
				.andExpect(redirectedUrl("/app")).andReturn().getResponse().getCookie("SESSION");
	}

	private Cookie loginWithRememberMe() throws Exception {
		return mockMvc
				.perform(post("/login").with(csrf()).param("email", EMAIL).param("password", PASSWORD)
						.param(HashingRememberMeServices.PARAMETER, "on"))
				.andExpect(redirectedUrl("/app")).andReturn().getResponse()
				.getCookie(HashingRememberMeServices.COOKIE_NAME);
	}

	private ResultActions withdrawLocal(Cookie session, String password, boolean confirm) throws Exception {
		var request = post("/app/settings/withdraw").with(csrf()).cookie(session).param("currentPassword", password);
		if (confirm) {
			request.param("confirm", "true");
		}
		return mockMvc.perform(request);
	}

	private long insertKakaoLandlord(String kakaoId) {
		jdbcTemplate
				.update("insert into landlord (auth_provider, kakao_id, email, email_verified_at, name, created_at, "
						+ "updated_at) values ('KAKAO', ?, 'kakao@example.com', now(), '김카카오', now(), now())", kakaoId);
		return jdbcTemplate.queryForObject("select max(id) from landlord", Long.class);
	}

	private Cookie loginKakao(String kakaoId) throws Exception {
		MvcResult callback = loginWithKakao(FakeKakaoServer.user(kakaoId, null, false, false, "김카카오"));
		assertThat(callback.getResponse().getRedirectedUrl()).isEqualTo("/app");
		return callback.getResponse().getCookie("SESSION");
	}

	private ResultActions confirmKakao(Cookie session) throws Exception {
		return mockMvc
				.perform(post("/app/settings/withdraw/kakao").with(csrf()).cookie(session).param("confirm", "true"))
				.andExpect(status().isOk());
	}

	/** 이미 로그인된 세션으로 시작한 카카오 재로그인의 콜백 */
	private MvcResult callback(MvcResult start, Cookie session, String kakaoId) throws Exception {
		kakao.returnUser(FakeKakaoServer.user(kakaoId, null, false, false, "김카카오"));
		String state = queryParam(start.getResponse().getRedirectedUrl(), "state");
		return mockMvc.perform(get("/login/oauth2/code/kakao").param("code", "fake-code").param("state", state)
				.cookie(sessionAfter(start, session))).andReturn();
	}

	// 세션이 이미 있으면 시작 응답에 새 쿠키가 없을 수 있다
	private static Cookie sessionAfter(MvcResult start, Cookie session) {
		Cookie renewed = start.getResponse().getCookie("SESSION");
		return renewed != null ? renewed : session;
	}

	private String statusOf(long id) {
		return jdbcTemplate.queryForObject("select status from landlord where id = ?", String.class, id);
	}

	private int count(String fromWhere) {
		return jdbcTemplate.queryForObject("select count(*) from " + fromWhere, Integer.class);
	}

}
