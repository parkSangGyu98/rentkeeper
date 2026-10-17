package com.rentkeeper.billing.application;

import java.sql.Date;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.rentkeeper.billing.domain.ChangeActor;
import com.rentkeeper.billing.domain.Charge;
import com.rentkeeper.billing.domain.ChargeRepository;
import com.rentkeeper.billing.domain.ChargeStatus;
import com.rentkeeper.billing.domain.ChargeStatusHistory;
import com.rentkeeper.billing.domain.ChargeStatusHistoryRepository;
import com.rentkeeper.common.ops.OpsAlertNotifier;

/**
 * 납부일 지남 전환 (ADR-023). {@link #run}을 하루 한 번 부른다(ChargeOverdueJob, 00:10 서울).
 * <p>
 * 대상: 받을 차례·일부 받음이면서 납부일이 오늘보다 앞선 청구. 세입자 신고(TENANT_REPORTED)는 빠진다(M3 결정 7).
 * 청구마다 별도 트랜잭션에서 도메인 메서드({@link Charge#refresh})로 바꾸고 이력을 남긴다. 같은 순간 임대인이 입금을
 * 기록해 낙관적 락이 충돌하면 그 청구는 건너뛴다(입금이 반영된 상태가 맞다. 아직 덜 받았으면 다음 실행이 다시 본다).
 */
@Component
public class ChargeOverdueMarker {

	private static final Logger log = LoggerFactory.getLogger(ChargeOverdueMarker.class);

	private final JdbcTemplate jdbcTemplate;

	private final ChargeRepository charges;

	private final ChargeStatusHistoryRepository statusHistories;

	private final OpsAlertNotifier opsAlerts;

	private final Clock clock;

	private final TransactionTemplate newTransaction;

	ChargeOverdueMarker(JdbcTemplate jdbcTemplate, ChargeRepository charges,
			ChargeStatusHistoryRepository statusHistories, OpsAlertNotifier opsAlerts, Clock clock,
			PlatformTransactionManager transactionManager) {
		this.jdbcTemplate = jdbcTemplate;
		this.charges = charges;
		this.statusHistories = statusHistories;
		this.opsAlerts = opsAlerts;
		this.clock = clock;
		this.newTransaction = new TransactionTemplate(transactionManager);
		this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	/**
	 * @param candidates
	 *            살펴본 청구 수
	 * @param marked
	 *            납부일 지남으로 바꾼 수
	 * @param conflicts
	 *            같은 순간 다른 변경(입금 등)과 겹쳐 건너뛴 수
	 * @param failed
	 *            실패한 수
	 */
	public record Result(int candidates, int marked, int conflicts, int failed) {
	}

	public Result run() {
		LocalDate today = LocalDate.now(clock);
		List<ChargeKey> keys = candidates(today);
		int marked = 0;
		int conflicts = 0;
		int failed = 0;
		for (ChargeKey key : keys) {
			try {
				if (Boolean.TRUE.equals(newTransaction.execute(status -> mark(key, today)))) {
					marked++;
				}
			} catch (OptimisticLockingFailureException e) {
				conflicts++;
			} catch (RuntimeException e) {
				failed++;
				log.error("charge overdue failed: chargeId={}, error={}", key.chargeId(), e.getClass().getSimpleName());
			}
		}
		Result result = new Result(keys.size(), marked, conflicts, failed);
		log.info("charge overdue done: today={}, {}", today, result);
		if (failed > 0) {
			opsAlerts.alert(ChargeGenerator.ALERT_CODE,
					"job=overdue failed=%d marked=%d candidates=%d".formatted(failed, marked, keys.size()));
		}
		return result;
	}

	private record ChargeKey(long chargeId, long landlordId) {
	}

	/**
	 * 모든 임대인의 대상 청구 id. 저장소 규칙(모든 조회에 landlordId, ADR-018)의 예외라 JdbcTemplate으로 id
	 * 쌍만 꺼내고, 바꿀 때는 landlordId로 다시 읽는다. ix_charge_status_due_date를 쓴다.
	 */
	private List<ChargeKey> candidates(LocalDate today) {
		List<String> statuses = ChargeStatus.overdueCandidates().stream().map(Enum::name).sorted().toList();
		String placeholders = String.join(", ", statuses.stream().map(s -> "?").toList());
		Object[] args = new Object[statuses.size() + 1];
		for (int i = 0; i < statuses.size(); i++) {
			args[i] = statuses.get(i);
		}
		args[statuses.size()] = Date.valueOf(today);
		return jdbcTemplate.query(
				"select id, landlord_id from charge where status in (" + placeholders
						+ ") and due_date < ? order by id",
				(rs, rowNum) -> new ChargeKey(rs.getLong("id"), rs.getLong("landlord_id")), args);
	}

	private boolean mark(ChargeKey key, LocalDate today) {
		Charge charge = charges.findByIdAndLandlordId(key.chargeId(), key.landlordId()).orElse(null);
		if (charge == null) {
			return false;
		}
		Instant now = clock.instant();
		return charge.refresh(today, now).map(change -> {
			statusHistories
					.save(ChargeStatusHistory.of(charge, change, ChangeActor.SYSTEM, ChargeIssuer.REASON_OVERDUE, now));
			// 낙관적 락 검사를 이 트랜잭션 안에서 끝낸다 (충돌이면 여기서 예외)
			charges.flush();
			return true;
		}).orElse(false);
	}

}
