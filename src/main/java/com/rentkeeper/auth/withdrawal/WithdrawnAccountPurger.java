package com.rentkeeper.auth.withdrawal;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.rentkeeper.auth.withdrawal.KakaoUnlinkPendingStore.AlertCounts;
import com.rentkeeper.auth.withdrawal.KakaoUnlinkPendingStore.Pending;
import com.rentkeeper.common.ops.OpsAlertNotifier;
import com.rentkeeper.landlord.application.LandlordService;

/**
 * 탈퇴 계정 파기와 카카오 연결 끊기 재시도 (AUTH-5, ADR-015). {@link #run()}을 하루 한 번 부른다
 * (WithdrawnAccountPurgeJob).
 * <ol>
 * <li><b>파기</b>: 탈퇴 후 30일이 지난 계정을 계정마다 별도 트랜잭션으로 지운다. 카카오 계정이 아직 끊기지 않았으면 같은
 * 트랜잭션 안에서 회원번호만 대기 행으로 옮겨 둔 뒤 지운다(둘 중 하나만 되는 일이 없게)</li>
 * <li><b>재시도</b>: 대기 행마다 연결 끊기를 다시 시도한다 (KakaoUnlinker)</li>
 * <li><b>알림</b>: 남은 대기 행으로 판정해 하루 한 줄 요약 (개인정보 없이 건수만)</li>
 * </ol>
 * 한 계정·한 행의 실패는 로그만 남기고 다음으로 넘어간다. 다시 돌아도 결과가 같다: 지운 계정은 다시 대상이 되지 않고, 대기 행 보장은
 * upsert다. 중간에 멈추면 진행 중이던 계정의 트랜잭션만 롤백되어 다음 실행에서 다시 처리된다.
 * <p>
 * <b>landlord를 참조하는 새 테이블은 ON DELETE CASCADE로 만든다.</b> 그래야 이 배치가 행째 지울 수 있다
 * (PurgeCascadeConventionTests가 검사). FK가 없는 테이블(세션 등)은 아래에서 직접 지운다.
 */
@Component
public class WithdrawnAccountPurger {

	static final Duration GRACE_PERIOD = Duration.ofDays(30);

	static final Duration SEVERE_AFTER = Duration.ofDays(60);

	static final int TRANSIENT_ALERT_THRESHOLD = 3;

	private static final Logger log = LoggerFactory.getLogger(WithdrawnAccountPurger.class);

	private final JdbcTemplate jdbcTemplate;

	private final KakaoUnlinkPendingStore pendingStore;

	private final KakaoUnlinker kakaoUnlinker;

	private final LandlordService landlordService;

	private final OpsAlertNotifier opsAlerts;

	private final Clock clock;

	private final TransactionTemplate newTransaction;

	WithdrawnAccountPurger(JdbcTemplate jdbcTemplate, KakaoUnlinkPendingStore pendingStore, KakaoUnlinker kakaoUnlinker,
			LandlordService landlordService, OpsAlertNotifier opsAlerts, Clock clock,
			PlatformTransactionManager transactionManager) {
		this.jdbcTemplate = jdbcTemplate;
		this.pendingStore = pendingStore;
		this.kakaoUnlinker = kakaoUnlinker;
		this.landlordService = landlordService;
		this.opsAlerts = opsAlerts;
		this.clock = clock;
		this.newTransaction = new TransactionTemplate(transactionManager);
		this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	public record Result(int purged, int purgeFailed, int unlinked, int unlinkFailed, int skippedActive) {
	}

	public Result run() {
		int[] purge = purgeExpired();
		int[] retry = retryPending();
		alertSummary();
		Result result = new Result(purge[0], purge[1], retry[0], retry[1], retry[2]);
		log.info("withdrawn account purge done: {}", result);
		return result;
	}

	/** @return {파기한 수, 실패한 수} */
	int[] purgeExpired() {
		Instant cutoff = clock.instant().minus(GRACE_PERIOD);
		List<Long> ids = jdbcTemplate.queryForList(
				"select id from landlord where status = 'WITHDRAWN' and withdrawn_at <= ? order by id", Long.class,
				Timestamp.from(cutoff));
		int purged = 0;
		int failed = 0;
		for (long id : ids) {
			try {
				if (Boolean.TRUE.equals(newTransaction.execute(status -> purgeOne(id, cutoff)))) {
					purged++;
				}
			} catch (RuntimeException e) {
				failed++;
				log.error("withdrawn account purge failed: landlordId={}, error={}", id, e.getClass().getSimpleName());
			}
		}
		return new int[]{purged, failed};
	}

	/** 한 계정을 지운다 (호출한 쪽의 새 트랜잭션 안). 이미 지워졌거나 대상이 아니면 false */
	boolean purgeOne(long id, Instant cutoff) {
		// 다시 확인하며 잠근다: 다른 실행이 동시에 같은 계정을 처리하지 않게
		List<PurgeTarget> rows = jdbcTemplate.query("""
				select auth_provider, kakao_id, kakao_unlinked_at, withdrawn_at from landlord
				where id = ? and status = 'WITHDRAWN' and withdrawn_at <= ? for update
				""",
				(rs, rowNum) -> new PurgeTarget(rs.getString("auth_provider"), rs.getString("kakao_id"),
						rs.getTimestamp("kakao_unlinked_at") != null, rs.getTimestamp("withdrawn_at").toInstant()),
				id, Timestamp.from(cutoff));
		if (rows.isEmpty()) {
			return false;
		}
		PurgeTarget target = rows.get(0);
		// 끊기가 남은 카카오 계정: 회원번호만 대기 행으로 남긴다. 같은 회원번호로 사용 중인 계정이 있으면 끊지 않으므로 남기지 않는다
		if ("KAKAO".equals(target.authProvider()) && !target.unlinked()
				&& !landlordService.hasActiveKakaoAccount(target.kakaoId())) {
			pendingStore.ensure(target.kakaoId(), target.withdrawnAt());
		}
		// FK가 없는 테이블 (principal 이름 = landlord id). 탈퇴 때 이미 지웠지만 안전장치로 한 번 더
		String principalName = String.valueOf(id);
		jdbcTemplate.update("delete from spring_session where principal_name = ?", principalName);
		jdbcTemplate.update("delete from persistent_logins where username = ?", principalName);
		// account_token, login_failure, consent_log는 ON DELETE CASCADE로 함께 지워진다
		jdbcTemplate.update("delete from landlord where id = ?", id);
		log.info("withdrawn account purged: landlordId={}", id);
		return true;
	}

	/** @return {끊은 수, 실패한 수, 사용 중 계정이 있어 건너뛴 수} */
	int[] retryPending() {
		int unlinked = 0;
		int failed = 0;
		int skipped = 0;
		for (Pending pending : pendingStore.findAll()) {
			try {
				var result = kakaoUnlinker.attempt(pending.kakaoId(), pending.withdrawnAt());
				if (result.isEmpty()) {
					skipped++;
				} else if (result.get().isFailure()) {
					failed++;
				} else {
					unlinked++;
				}
			} catch (RuntimeException e) {
				failed++;
				// 회원번호는 로그에 남기지 않는다
				log.error("kakao unlink retry failed: error={}", e.getClass().getSimpleName());
			}
		}
		return new int[]{unlinked, failed, skipped};
	}

	/** 해결될 때까지 매일 다시 남긴다. 하루 한 줄, 건수만 */
	void alertSummary() {
		AlertCounts counts = pendingStore.countAlerts(clock.instant().minus(SEVERE_AFTER), TRANSIENT_ALERT_THRESHOLD);
		if (counts.needsAlert()) {
			opsAlerts.alert(KakaoUnlinker.ALERT_CODE,
					"source=daily config=%d transient=%d rejected=%d severe=%d pending=%d".formatted(counts.config(),
							counts.transientRepeated(), counts.rejected(), counts.severe(), counts.pending()));
		}
	}

	private record PurgeTarget(String authProvider, String kakaoId, boolean unlinked, Instant withdrawnAt) {
	}

}
