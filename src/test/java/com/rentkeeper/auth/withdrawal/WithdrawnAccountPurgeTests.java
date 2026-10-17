package com.rentkeeper.auth.withdrawal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.rentkeeper.auth.token.AccountTokenService;
import com.rentkeeper.auth.token.TokenPurpose;
import com.rentkeeper.support.KakaoIntegrationTest;
import com.rentkeeper.support.MutableClock;
import com.rentkeeper.support.MutableClockConfiguration;
import com.rentkeeper.support.TestData;

/**
 * 탈퇴 계정 30일 뒤 파기와 카카오 연결 끊기 재시도·알림 (ADR-015, V11). 가짜 카카오 서버로 연결 끊기 응답을 바꿔 가며
 * 시험한다.
 */
@ExtendWith(OutputCaptureExtension.class)
class WithdrawnAccountPurgeTests extends KakaoIntegrationTest {

	private static final Instant NOW = MutableClockConfiguration.START;

	private static final Instant EXPIRED = NOW.minus(Duration.ofDays(30)).minusSeconds(1);

	private static final Instant NOT_YET = NOW.minus(Duration.ofDays(30)).plusSeconds(60);

	private static final String KAKAO_ID = "4000000001";

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	WithdrawnAccountPurger purger;

	@Autowired
	AccountTokenService tokenService;

	@Autowired
	MutableClock clock;

	@Autowired
	@Qualifier("requestMappingHandlerMapping")
	RequestMappingHandlerMapping handlerMapping;

	@BeforeEach
	void setUp() {
		clock.set(NOW);
		TestData.reset(jdbcTemplate);
		jdbcTemplate.update("delete from persistent_logins");
		kakao.reset();
	}

	@AfterEach
	void dropFailureTrigger() {
		jdbcTemplate.execute("drop trigger if exists test_fail_purge on landlord");
		jdbcTemplate.execute("drop function if exists test_fail_purge()");
	}

	// ---------------------------------------------------------------- 파기

	@Test
	void purgesOnlyAfterThirtyDaysWithAllLinkedRows() {
		long expired = insertLocal("old@example.com", EXPIRED);
		long notYet = insertLocal("new@example.com", NOT_YET);
		long active = insertLocal("active@example.com", null);
		tokenService.issue(expired, TokenPurpose.PASSWORD_RESET, null);
		jdbcTemplate.update("insert into login_failure (landlord_id, failed_count, last_failed_at, created_at, "
				+ "updated_at) values (?, 1, now(), now(), now())", expired);
		jdbcTemplate.update("insert into consent_log (landlord_id, document_type, document_version, agreed_at) "
				+ "values (?, 'TERMS', 'v0.1', now())", expired);
		jdbcTemplate.update(
				"insert into persistent_logins (username, series, token, last_used) values (?, 's1', repeat('a', 64), now())",
				String.valueOf(expired));

		WithdrawnAccountPurger.Result result = purger.run();

		assertThat(result.purged()).isOne();
		assertThat(ids()).containsExactlyInAnyOrder(notYet, active);
		for (String table : new String[]{"account_token", "login_failure", "consent_log"}) {
			assertThat(count(table + " where landlord_id = " + expired)).as(table).isZero();
		}
		assertThat(count("persistent_logins where username = '" + expired + "'")).isZero();
		assertThat(count("kakao_unlink_pending")).as("이메일 계정은 대기 행이 없다").isZero();
		assertThat(kakao.requests()).isEmpty();
	}

	@Test
	void kakaoAccountNotYetUnlinkedLeavesOnlyKakaoIdInPendingAndRetries() {
		insertKakao(KAKAO_ID, "WITHDRAWN", EXPIRED, false);
		kakao.unlinkStatus(500);

		purger.run();

		assertThat(count("landlord")).as("이름·이메일 등은 예정대로 파기").isZero();
		Map<String, Object> pending = jdbcTemplate.queryForMap("select * from kakao_unlink_pending");
		assertThat(pending).containsOnlyKeys("kakao_id", "withdrawn_at", "failure_count", "last_failed_at",
				"last_failure_type");
		assertThat(pending).containsEntry("kakao_id", KAKAO_ID).containsEntry("withdrawn_at", Timestamp.from(EXPIRED))
				.containsEntry("failure_count", 1).containsEntry("last_failure_type", "TRANSIENT");

		// 다음 날 성공하면 행을 바로 지운다
		kakao.unlinkStatus(200);
		clock.advance(Duration.ofDays(1));
		purger.run();
		assertThat(count("kakao_unlink_pending")).isZero();
		assertThat(kakao.requestsTo("/v1/user/unlink")).hasSize(2);
	}

	@Test
	void alreadyUnlinkedKakaoAccountIsPurgedWithoutPendingOrCall() {
		insertKakao(KAKAO_ID, "WITHDRAWN", EXPIRED, true);

		purger.run();

		assertThat(count("landlord")).isZero();
		assertThat(count("kakao_unlink_pending")).isZero();
		assertThat(kakao.requests()).isEmpty();
	}

	@Test
	void activeAccountWithSameKakaoIdIsNeverUnlinked() {
		insertKakao(KAKAO_ID, "WITHDRAWN", EXPIRED, false);
		long active = insertKakao(KAKAO_ID, "ACTIVE", null, false);
		// 다른 회원번호: 대기 행만 남아 있지만 같은 회원번호의 사용 중 계정이 있다
		insertKakao("4000000002", "ACTIVE", null, false);
		jdbcTemplate.update(
				"insert into kakao_unlink_pending (kakao_id, withdrawn_at, failure_count, last_failed_at, "
						+ "last_failure_type) values ('4000000002', ?, 1, ?, 'TRANSIENT')",
				Timestamp.from(EXPIRED), Timestamp.from(EXPIRED));

		WithdrawnAccountPurger.Result result = purger.run();

		assertThat(ids()).as("탈퇴 계정은 파기, 사용 중 계정은 그대로").contains(active).hasSize(2);
		assertThat(count("kakao_unlink_pending")).as("다시 동의해 쓰는 연결이므로 대기 행을 지운다").isZero();
		assertThat(kakao.requestsTo("/v1/user/unlink")).isEmpty();
		assertThat(result.skippedActive()).isOne();
	}

	@Test
	void oneFailingAccountDoesNotStopOthersAndRollsBackItsPendingRow() {
		long failing = insertKakao(KAKAO_ID, "WITHDRAWN", EXPIRED, false);
		long other = insertLocal("other@example.com", EXPIRED);
		failDeleteOf(failing);

		WithdrawnAccountPurger.Result result = purger.run();

		assertThat(result.purged()).isOne();
		assertThat(result.purgeFailed()).isOne();
		assertThat(ids()).containsExactly(failing).doesNotContain(other);
		assertThat(count("kakao_unlink_pending")).as("대기 행 생성과 landlord 삭제는 같은 트랜잭션: 함께 롤백").isZero();
	}

	@Test
	void runningTwiceGivesSameResult() {
		insertKakao(KAKAO_ID, "WITHDRAWN", EXPIRED, false);
		insertLocal("old@example.com", EXPIRED);

		WithdrawnAccountPurger.Result first = purger.run();
		WithdrawnAccountPurger.Result second = purger.run();

		assertThat(first.purged()).isEqualTo(2);
		assertThat(first.unlinked()).isOne();
		assertThat(second).isEqualTo(new WithdrawnAccountPurger.Result(0, 0, 0, 0, 0));
		assertThat(count("landlord") + count("kakao_unlink_pending")).isZero();
		assertThat(kakao.requestsTo("/v1/user/unlink")).hasSize(1);
	}

	@Test
	void unlinkSuccessMarksEveryWithdrawnRowOfSameKakaoId() {
		// 탈퇴(끊기 실패) → 재가입 → 다시 탈퇴(끊기 실패): 같은 회원번호의 탈퇴 행 두 개, 대기 행 하나
		long first = insertKakao(KAKAO_ID, "WITHDRAWN", NOW.minus(Duration.ofDays(10)), false);
		long second = insertKakao(KAKAO_ID, "WITHDRAWN", NOW.minus(Duration.ofDays(2)), false);
		jdbcTemplate.update(
				"insert into kakao_unlink_pending (kakao_id, withdrawn_at, failure_count, last_failed_at, "
						+ "last_failure_type) values (?, ?, 2, ?, 'TRANSIENT')",
				KAKAO_ID, Timestamp.from(NOW.minus(Duration.ofDays(10))),
				Timestamp.from(NOW.minus(Duration.ofDays(2))));

		purger.run();

		assertThat(jdbcTemplate.queryForList("select kakao_unlinked_at from landlord where id in (?, ?)",
				Timestamp.class, first, second)).containsOnly(Timestamp.from(NOW));
		assertThat(count("kakao_unlink_pending")).isZero();

		// 30일이 지나 둘 다 파기될 때 이미 끊긴 회원을 또 끊으려 하지 않는다
		clock.advance(Duration.ofDays(31));
		purger.run();
		assertThat(count("landlord") + count("kakao_unlink_pending")).isZero();
		assertThat(kakao.requestsTo("/v1/user/unlink")).hasSize(1);
	}

	@Test
	void pendingUpsertKeepsOneRowWithEarliestWithdrawal() {
		KakaoUnlinkPendingStore store = new KakaoUnlinkPendingStore(jdbcTemplate);
		store.recordFailure(KAKAO_ID, NOW.minus(Duration.ofDays(2)),
				com.rentkeeper.auth.kakao.KakaoUnlinkResult.TRANSIENT, NOW);
		store.ensure(KAKAO_ID, NOW.minus(Duration.ofDays(9)));
		store.recordFailure(KAKAO_ID, NOW.minus(Duration.ofDays(1)),
				com.rentkeeper.auth.kakao.KakaoUnlinkResult.REJECTED, NOW.plusSeconds(5));

		assertThat(jdbcTemplate.queryForMap("select * from kakao_unlink_pending"))
				.containsEntry("withdrawn_at", Timestamp.from(NOW.minus(Duration.ofDays(9))))
				.containsEntry("failure_count", 2).containsEntry("last_failure_type", "REJECTED")
				.containsEntry("last_failed_at", Timestamp.from(NOW.plusSeconds(5)));
	}

	// ---------------------------------------------------------------- 재시도·실패 분류·알림

	@Test
	void failuresAreClassifiedAndCounted() {
		insertPending("4000000011");
		kakao.unlinkStatus(401);
		purger.run();
		assertThat(failureType("4000000011")).isEqualTo("CONFIG");

		kakao.unlinkStatus(400);
		purger.run();
		assertThat(failureType("4000000011")).isEqualTo("REJECTED");

		kakao.unlinkStatus(503);
		purger.run();
		assertThat(failureType("4000000011")).isEqualTo("TRANSIENT");
		assertThat(jdbcTemplate.queryForObject("select failure_count from kakao_unlink_pending", Integer.class))
				.isEqualTo(3);
	}

	@Test
	void configErrorAlertsOnFirstFailure(CapturedOutput output) {
		insertPending(KAKAO_ID);
		kakao.unlinkStatus(403);

		purger.run();

		assertThat(output)
				.contains("KAKAO_UNLINK_ALERT source=daily config=1 transient=0 rejected=0 severe=0 pending=1");
	}

	@Test
	void transientErrorAlertsFromThirdConsecutiveFailureAndRepeatsDaily(CapturedOutput output) {
		insertPending(KAKAO_ID);
		kakao.unlinkStatus(500);

		purger.run();
		purger.run();
		assertThat(output).doesNotContain("KAKAO_UNLINK_ALERT");

		purger.run();
		assertThat(output).contains("KAKAO_UNLINK_ALERT source=daily config=0 transient=1");

		// 해결될 때까지 매일 다시
		int before = occurrences(output.getAll(), "KAKAO_UNLINK_ALERT");
		purger.run();
		assertThat(occurrences(output.getAll(), "KAKAO_UNLINK_ALERT")).isEqualTo(before + 1);
	}

	@Test
	void rejectedAlertsOnFirstFailure(CapturedOutput output) {
		insertPending(KAKAO_ID);
		kakao.unlinkResponse(400, "{\"msg\": \"invalid param\", \"code\": -2}");

		purger.run();

		assertThat(failureType(KAKAO_ID)).isEqualTo("REJECTED");
		assertThat(output).contains("rejected=1").contains("kakao unlink failed: status=400 kakaoCode=-2")
				.doesNotContain("invalid param");
	}

	@Test
	void notLinkedResponseIsTreatedAsAlreadyUnlinked(CapturedOutput output) {
		// 이미 끊긴 회원을 다시 끊으면 실제 카카오는 400 + code -101을 준다 (개발자 확인, ADR-015)
		long withdrawn = insertKakao(KAKAO_ID, "WITHDRAWN", NOW.minus(Duration.ofDays(3)), false);
		jdbcTemplate.update(
				"insert into kakao_unlink_pending (kakao_id, withdrawn_at, failure_count, last_failed_at, "
						+ "last_failure_type) values (?, ?, 1, ?, 'TRANSIENT')",
				KAKAO_ID, Timestamp.from(NOW.minus(Duration.ofDays(3))), Timestamp.from(NOW.minus(Duration.ofDays(1))));
		kakao.unlinkResponse(400, "{\"msg\": \"NotRegisteredUserException\", \"code\": -101}");

		WithdrawnAccountPurger.Result result = purger.run();

		assertThat(result.unlinked()).isOne();
		assertThat(count("kakao_unlink_pending")).isZero();
		assertThat(jdbcTemplate.queryForObject("select kakao_unlinked_at from landlord where id = ?", Timestamp.class,
				withdrawn)).isEqualTo(Timestamp.from(NOW));
		assertThat(output).doesNotContain("KAKAO_UNLINK_ALERT").doesNotContain("kakao unlink failed")
				.contains("status=400 kakaoCode=-101").doesNotContain("NotRegisteredUserException");
	}

	@Test
	void ambiguous400ResponsesStayRejected() {
		// 코드가 없는 400, JSON이 아닌 400은 "이미 끊김"으로 단정하지 않는다
		for (String body : new String[]{"{\"msg\": \"no code\"}", "<html>Bad Request</html>", ""}) {
			TestData.reset(jdbcTemplate);
			insertPending(KAKAO_ID);
			kakao.unlinkResponse(400, body);

			purger.run();

			assertThat(failureType(KAKAO_ID)).as(body).isEqualTo("REJECTED");
		}
	}

	@Test
	void sixtyDaysWithoutUnlinkIsSevere(CapturedOutput output) {
		jdbcTemplate.update("insert into kakao_unlink_pending (kakao_id, withdrawn_at) values (?, ?)", KAKAO_ID,
				Timestamp.from(NOW.minus(Duration.ofDays(60))));
		kakao.unlinkStatus(500);

		purger.run();

		assertThat(output).contains("severe=1");
	}

	@Test
	void logsNeverContainKakaoIdOrAdminKey(CapturedOutput output) {
		insertKakao(KAKAO_ID, "WITHDRAWN", EXPIRED, false);
		kakao.unlinkStatus(401);

		purger.run();

		assertThat(output).contains("KAKAO_UNLINK_ALERT").doesNotContain(KAKAO_ID).doesNotContain("test-admin-key")
				.doesNotContain("fake failure");
	}

	// ---------------------------------------------------------------- 스키마 (V11)

	@Test
	void pendingTableRejectsInvalidRows() {
		Timestamp now = Timestamp.from(NOW);
		assertThatThrownBy(() -> jdbcTemplate
				.update("insert into kakao_unlink_pending (kakao_id, withdrawn_at) values ('abc', ?)", now))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("ck_kakao_unlink_pending_kakao_id_digits");
		assertThatThrownBy(
				() -> jdbcTemplate.update(
						"insert into kakao_unlink_pending (kakao_id, withdrawn_at, "
								+ "failure_count, last_failed_at, last_failure_type) values ('1', ?, 1, ?, 'OTHER')",
						now, now))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("ck_kakao_unlink_pending_failure_type");
		assertThatThrownBy(() -> jdbcTemplate.update(
				"insert into kakao_unlink_pending (kakao_id, withdrawn_at, failure_count) values ('1', ?, 1)", now))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("ck_kakao_unlink_pending_failure_fields");
		assertThatThrownBy(() -> jdbcTemplate.update(
				"insert into kakao_unlink_pending (kakao_id, withdrawn_at, failure_count) values ('1', ?, -1)", now))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void pendingTableHasNoForeignKeyToLandlord() {
		assertThat(jdbcTemplate.queryForObject("""
				select count(*) from information_schema.table_constraints
				where table_name = 'kakao_unlink_pending' and constraint_type = 'FOREIGN KEY'
				""", Integer.class)).isZero();
	}

	@Test
	void everyForeignKeyToLandlordCascadesSoPurgeCanDeleteTheRow() {
		// landlord를 참조하는 새 테이블이 CASCADE를 빠뜨리면 파기 배치가 그 계정을 지우지 못한다
		// (WithdrawnAccountPurger)
		assertThat(jdbcTemplate.queryForList("""
				select tc.table_name || '.' || tc.constraint_name
				from information_schema.table_constraints tc
				join information_schema.referential_constraints rc on rc.constraint_name = tc.constraint_name
				join information_schema.constraint_column_usage ccu on ccu.constraint_name = tc.constraint_name
				where tc.constraint_type = 'FOREIGN KEY' and ccu.table_name = 'landlord' and rc.delete_rule <> 'CASCADE'
				""", String.class)).isEmpty();
	}

	// ---------------------------------------------------------------- 수동 실행 경로

	@Test
	void purgeHasNoHttpEndpoint() {
		assertThat(handlerMapping.getHandlerMethods().keySet()).allSatisfy(
				info -> assertThat(info.getPatternValues()).noneMatch(path -> path.toLowerCase().contains("purge")));
	}

	// ---------------------------------------------------------------- 도우미

	private long insertLocal(String email, Instant withdrawnAt) {
		jdbcTemplate.update(
				"insert into landlord (auth_provider, email, password_hash, name, status, withdrawn_at, "
						+ "created_at, updated_at) values ('LOCAL', ?, '{bcrypt}x', '김임대', ?, ?, now(), now())",
				email, withdrawnAt == null ? "ACTIVE" : "WITHDRAWN",
				withdrawnAt == null ? null : Timestamp.from(withdrawnAt));
		return jdbcTemplate.queryForObject("select max(id) from landlord", Long.class);
	}

	private long insertKakao(String kakaoId, String status, Instant withdrawnAt, boolean unlinked) {
		jdbcTemplate.update("insert into landlord (auth_provider, kakao_id, name, status, withdrawn_at, "
				+ "kakao_unlinked_at, created_at, updated_at) values ('KAKAO', ?, '김카카오', ?, ?, ?, now(), now())",
				kakaoId, status, withdrawnAt == null ? null : Timestamp.from(withdrawnAt),
				unlinked ? Timestamp.from(withdrawnAt) : null);
		return jdbcTemplate.queryForObject("select max(id) from landlord", Long.class);
	}

	private void insertPending(String kakaoId) {
		jdbcTemplate.update("insert into kakao_unlink_pending (kakao_id, withdrawn_at) values (?, ?)", kakaoId,
				Timestamp.from(EXPIRED));
	}

	/** 이 계정을 지우려 하면 DB 오류가 나게 한다 (한 계정의 실패를 흉내 냄) */
	private void failDeleteOf(long landlordId) {
		jdbcTemplate.execute("""
				create function test_fail_purge() returns trigger language plpgsql as $$
				begin raise exception 'test failure'; end $$
				""");
		jdbcTemplate.execute("create trigger test_fail_purge before delete on landlord for each row when (old.id = "
				+ landlordId + ") execute function test_fail_purge()");
	}

	private java.util.List<Long> ids() {
		return jdbcTemplate.queryForList("select id from landlord", Long.class);
	}

	private String failureType(String kakaoId) {
		return jdbcTemplate.queryForObject("select last_failure_type from kakao_unlink_pending where kakao_id = ?",
				String.class, kakaoId);
	}

	private int count(String fromWhere) {
		return jdbcTemplate.queryForObject("select count(*) from " + fromWhere, Integer.class);
	}

	private static int occurrences(String text, String word) {
		return text.split(word, -1).length - 1;
	}

}
