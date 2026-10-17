package com.rentkeeper.billing.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.rentkeeper.billing.application.ChargePlanner.PlannedCharge;
import com.rentkeeper.billing.domain.ChangeActor;
import com.rentkeeper.billing.domain.ChargeRepository;
import com.rentkeeper.billing.domain.ChargeSource;
import com.rentkeeper.common.jpa.Constraints;
import com.rentkeeper.common.ops.OpsAlertNotifier;
import com.rentkeeper.lease.application.LeaseBillingService;
import com.rentkeeper.lease.application.LeaseBillingService.LeaseKey;
import com.rentkeeper.lease.application.LeaseBillingTerms;

/**
 * 청구 자동 생성 (CHG-1·2, ADR-024). {@link #run}을 하루 한 번 부른다(ChargeGenerationJob,
 * 03:00 서울).
 * <p>
 * 계약마다 별도 트랜잭션으로 처리한다: 계약 행을 잠그고(수정·종료와 겹치지 않게), 이미 있는 달을 확인한 뒤 빠진 달을 모두 만든다(누락
 * 복구). 한 계약이 실패해도 나머지는 계속한다. 다시 돌아도 결과가 같다: 이미 있는 달은 건너뛰고, 동시에 다른 쪽이 같은 달을 만들면
 * UNIQUE(uk_charge_lease_month)가 막는다. 실패가 있으면 운영 알림(CHARGE_BATCH_ALERT)을 건수만
 * 남긴다.
 */
@Component
public class ChargeGenerator {

	public static final String ALERT_CODE = "CHARGE_BATCH_ALERT";

	private static final Logger log = LoggerFactory.getLogger(ChargeGenerator.class);

	private final LeaseBillingService leaseBilling;

	private final ChargeRepository charges;

	private final ChargeIssuer issuer;

	private final OpsAlertNotifier opsAlerts;

	private final Clock clock;

	private final TransactionTemplate newTransaction;

	ChargeGenerator(LeaseBillingService leaseBilling, ChargeRepository charges, ChargeIssuer issuer,
			OpsAlertNotifier opsAlerts, Clock clock, PlatformTransactionManager transactionManager) {
		this.leaseBilling = leaseBilling;
		this.charges = charges;
		this.issuer = issuer;
		this.opsAlerts = opsAlerts;
		this.clock = clock;
		this.newTransaction = new TransactionTemplate(transactionManager);
		this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	/**
	 * @param leases
	 *            살펴본 계약 수
	 * @param created
	 *            만든 청구 수
	 * @param duplicated
	 *            다른 쪽이 먼저 만들어 건너뛴 계약 수 (다음 실행에서 다시 본다)
	 * @param failed
	 *            실패한 계약 수 (다음 실행에서 다시 본다)
	 */
	public record Result(int leases, int created, int duplicated, int failed) {
	}

	/** 오늘(서울, 주입한 Clock) 기준으로 만든다 */
	public Result run() {
		LocalDate today = LocalDate.now(clock);
		List<LeaseKey> keys = leaseBilling
				.billableLeases(YearMonth.from(today).minusMonths(ChargePlanner.RECOVERY_MONTHS).atDay(1));
		int created = 0;
		int duplicated = 0;
		int failed = 0;
		for (LeaseKey key : keys) {
			try {
				created += newTransaction.execute(status -> generate(key, today));
			} catch (DataIntegrityViolationException e) {
				if (Constraints.isViolated(e, "uk_charge_lease_month")) {
					duplicated++;
				} else {
					failed++;
					log.error("charge generation failed: leaseId={}, error={}", key.leaseId(),
							e.getClass().getSimpleName());
				}
			} catch (RuntimeException e) {
				failed++;
				log.error("charge generation failed: leaseId={}, error={}", key.leaseId(),
						e.getClass().getSimpleName());
			}
		}
		Result result = new Result(keys.size(), created, duplicated, failed);
		log.info("charge generation done: today={}, {}", today, result);
		if (failed > 0) {
			opsAlerts.alert(ALERT_CODE,
					"job=generation failed=%d created=%d leases=%d".formatted(failed, created, keys.size()));
		}
		return result;
	}

	/** 한 계약의 빠진 달을 만든다 (계약별 새 트랜잭션 안) */
	private int generate(LeaseKey key, LocalDate today) {
		Optional<LeaseBillingTerms> terms = leaseBilling.lockForBilling(key.leaseId(), key.landlordId());
		if (terms.isEmpty()) {
			return 0; // 목록을 뽑은 뒤 지워진 계약
		}
		Set<YearMonth> existing = charges.billingMonths(key.leaseId(), key.landlordId()).stream().map(YearMonth::from)
				.collect(Collectors.toSet());
		List<PlannedCharge> planned = ChargePlanner.plan(terms.get(), today, existing);
		for (PlannedCharge charge : planned) {
			issuer.createAndIssue(key.landlordId(), key.leaseId(), charge, ChargeSource.AUTO, ChangeActor.SYSTEM, today,
					clock.instant());
		}
		return planned.size();
	}

}
