package com.rentkeeper.billing.application;

import java.time.Instant;
import java.time.LocalDate;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.rentkeeper.billing.application.ChargePlanner.PlannedCharge;
import com.rentkeeper.billing.domain.ChangeActor;
import com.rentkeeper.billing.domain.Charge;
import com.rentkeeper.billing.domain.ChargeItem;
import com.rentkeeper.billing.domain.ChargeItemHistory;
import com.rentkeeper.billing.domain.ChargeItemHistoryRepository;
import com.rentkeeper.billing.domain.ChargeItemRepository;
import com.rentkeeper.billing.domain.ChargeItemType;
import com.rentkeeper.billing.domain.ChargeRepository;
import com.rentkeeper.billing.domain.ChargeSource;
import com.rentkeeper.billing.domain.ChargeStatusHistory;
import com.rentkeeper.billing.domain.ChargeStatusHistoryRepository;
import com.rentkeeper.billing.domain.StatusChange;

/**
 * 계산한 청구 한 달치를 저장하고 발행한다 (호출한 쪽의 트랜잭션 안). 청구, 항목(월세·관리비, 0원이면 생략), 상태 이력(만듦 →
 * 발행 → 납부일이 지났으면 납부일 지남), 항목 이력을 함께 남긴다.
 */
@Component
public class ChargeIssuer {

	static final String REASON_CREATED = "자동 생성";

	static final String REASON_ISSUED = "자동 발행 — 알림 발송은 M5";

	static final String REASON_OVERDUE = "납부일 지남";

	private final ChargeRepository charges;

	private final ChargeItemRepository items;

	private final ChargeStatusHistoryRepository statusHistories;

	private final ChargeItemHistoryRepository itemHistories;

	ChargeIssuer(ChargeRepository charges, ChargeItemRepository items, ChargeStatusHistoryRepository statusHistories,
			ChargeItemHistoryRepository itemHistories) {
		this.charges = charges;
		this.items = items;
		this.statusHistories = statusHistories;
		this.itemHistories = itemHistories;
	}

	/**
	 * @throws org.springframework.dao.DataIntegrityViolationException
	 *             같은 달 청구가 이미 있을 때 (uk_charge_lease_month, 다른 요청이 먼저 만듦)
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public Charge createAndIssue(long landlordId, long leaseId, PlannedCharge planned, ChargeSource source,
			ChangeActor actor, LocalDate today, Instant now) {
		Charge charge = charges.saveAndFlush(
				Charge.create(landlordId, leaseId, planned.month(), planned.dueDate(), planned.total(), source));
		record(charge, charge.created(), actor, REASON_CREATED, now);
		addItem(charge, ChargeItemType.RENT, planned.monthlyRent(), actor, now);
		addItem(charge, ChargeItemType.MAINTENANCE, planned.maintenanceFee(), actor, now);
		record(charge, charge.issue(now), actor, REASON_ISSUED, now);
		charge.refresh(today, now).ifPresent(change -> record(charge, change, actor, REASON_OVERDUE, now));
		return charge;
	}

	private void addItem(Charge charge, ChargeItemType type, long amount, ChangeActor actor, Instant now) {
		if (amount == 0) {
			return;
		}
		ChargeItem item = items.saveAndFlush(ChargeItem.of(charge, type, type.getLabel(), amount));
		itemHistories.save(ChargeItemHistory.added(item, actor, now));
	}

	private void record(Charge charge, StatusChange change, ChangeActor actor, String reason, Instant now) {
		statusHistories.save(ChargeStatusHistory.of(charge, change, actor, reason, now));
	}

}
