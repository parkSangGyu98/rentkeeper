package com.rentkeeper.billing.application;

import org.hibernate.StaleStateException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.rentkeeper.billing.application.ChargeExceptions.ChargeCanceledException;
import com.rentkeeper.billing.application.ChargeExceptions.ChargeChangedException;
import com.rentkeeper.billing.domain.Charge;
import com.rentkeeper.billing.domain.ChargeRepository;
import com.rentkeeper.billing.domain.ChargeStatus;
import com.rentkeeper.common.error.ResourceNotFoundException;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.OptimisticLockException;

/**
 * 임대인이 받을 월세를 고치는 작업(입금·항목·취소)의 시작과 끝 (M3-b 결정 4, ADR-025).
 * <ul>
 * <li>시작: 화면이 보던 version과 다르면 바로 거절한다</li>
 * <li>끝: 작업마다 version을 정확히 1 올린다. 받은 금액·합계가 바뀌면 그 UPDATE가 올리고, 바뀌지 않으면(같은 금액 정정
 * 등) 강제로 올린다. 같은 순간 다른 작업이 먼저 커밋했으면 여기서 실패해 이 작업 전체(입금 기록 포함)가 되돌려진다</li>
 * </ul>
 */
@Component
class ChargeChanges {

	private final ChargeRepository charges;

	private final EntityManager entityManager;

	ChargeChanges(ChargeRepository charges, EntityManager entityManager) {
		this.charges = charges;
		this.entityManager = entityManager;
	}

	/**
	 * @throws ChargeChangedException
	 *             화면이 보던 뒤로 바뀌었을 때
	 * @throws ChargeCanceledException
	 *             취소한 청구
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	Charge start(long landlordId, long chargeId, long seenVersion) {
		Charge charge = charges.findByIdAndLandlordId(chargeId, landlordId)
				.orElseThrow(() -> new ResourceNotFoundException("charge"));
		if (charge.getVersion() != seenVersion) {
			throw new ChargeChangedException();
		}
		if (charge.getStatus() == ChargeStatus.CANCELED) {
			throw new ChargeCanceledException();
		}
		return charge;
	}

	/**
	 * @throws ChargeChangedException
	 *             같은 순간 다른 작업이 먼저 바꿨을 때
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	void finish(Charge charge, long versionBefore) {
		try {
			charges.flush();
			if (charge.getVersion() == versionBefore) {
				// 행을 잠그고 version을 바로 올린다 (UPDATE … WHERE version = ?)
				entityManager.lock(charge, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
				entityManager.flush();
			}
		} catch (OptimisticLockingFailureException | OptimisticLockException | StaleStateException e) {
			throw new ChargeChangedException(e);
		}
	}

}
