package com.rentkeeper.billing.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.rentkeeper.billing.application.ChargeExceptions.HasPaymentsException;
import com.rentkeeper.billing.application.ChargeExceptions.LastItemException;
import com.rentkeeper.billing.application.ChargeExceptions.ReasonInvalidException;
import com.rentkeeper.billing.application.ChargeExceptions.ZeroTotalException;
import com.rentkeeper.billing.domain.ChangeActor;
import com.rentkeeper.billing.domain.Charge;
import com.rentkeeper.billing.domain.ChargeItem;
import com.rentkeeper.billing.domain.ChargeItemHistory;
import com.rentkeeper.billing.domain.ChargeItemHistoryRepository;
import com.rentkeeper.billing.domain.ChargeItemRepository;
import com.rentkeeper.billing.domain.ChargeItemType;
import com.rentkeeper.billing.domain.ChargeStatus;
import com.rentkeeper.billing.domain.ChargeStatusHistory;
import com.rentkeeper.billing.domain.ChargeStatusHistoryRepository;
import com.rentkeeper.common.error.ResourceNotFoundException;

/**
 * 받을 월세 항목 추가·고치기·지우기와 취소 (CHG-3, M3 결정 7·8·12, M3-b 결정 9·14). 항목이 바뀔 때마다 항목 변경
 * 이력(누가· 언제·이전 금액)을 남기고 합계를 다시 계산한다. 합계가 0원이 되는 변경과 마지막 항목 삭제는 막고 취소를 안내한다. 화면이
 * 보던 version과 다르면 저장하지 않는다(ChargeChanges). 남의 청구·항목이면 404.
 */
@Service
@Transactional
public class ChargeEditService {

	static final String REASON_ITEMS_CHANGED = "항목 수정";

	private final ChargeChanges changes;

	private final ChargeItemRepository items;

	private final ChargeItemHistoryRepository itemHistories;

	private final ChargeStatusHistoryRepository statusHistories;

	private final Clock clock;

	ChargeEditService(ChargeChanges changes, ChargeItemRepository items, ChargeItemHistoryRepository itemHistories,
			ChargeStatusHistoryRepository statusHistories, Clock clock) {
		this.changes = changes;
		this.items = items;
		this.itemHistories = itemHistories;
		this.statusHistories = statusHistories;
		this.clock = clock;
	}

	/** 항목 입력 (형식 검사를 마친 값). 고칠 때 종류는 쓰지 않는다 */
	public record ItemInput(ChargeItemType type, String label, long amount, String memo) {
	}

	/** @return 바뀐 뒤의 상태 */
	public ChargeStatus addItem(long landlordId, long chargeId, long seenVersion, ItemInput input) {
		Charge charge = changes.start(landlordId, chargeId, seenVersion);
		long versionBefore = charge.getVersion();
		requirePositive(total(landlordId, chargeId) + input.amount());
		ChargeItem item = items
				.saveAndFlush(ChargeItem.of(charge, input.type(), input.label(), input.amount(), input.memo()));
		itemHistories.save(ChargeItemHistory.added(item, actor(landlordId), clock.instant()));
		return finish(landlordId, charge, versionBefore);
	}

	/** @return 바뀐 뒤의 상태 */
	public ChargeStatus changeItem(long landlordId, long chargeId, long itemId, long seenVersion, ItemInput input) {
		Charge charge = changes.start(landlordId, chargeId, seenVersion);
		long versionBefore = charge.getVersion();
		ChargeItem item = item(landlordId, chargeId, itemId);
		long before = item.getAmount();
		requirePositive(total(landlordId, chargeId) - before + input.amount());
		item.change(input.label(), input.amount(), input.memo());
		items.saveAndFlush(item);
		itemHistories.save(ChargeItemHistory.changed(item, before, actor(landlordId), clock.instant()));
		return finish(landlordId, charge, versionBefore);
	}

	/** @return 바뀐 뒤의 상태 */
	public ChargeStatus removeItem(long landlordId, long chargeId, long itemId, long seenVersion) {
		Charge charge = changes.start(landlordId, chargeId, seenVersion);
		long versionBefore = charge.getVersion();
		ChargeItem item = item(landlordId, chargeId, itemId);
		List<ChargeItem> all = items.findByChargeIdAndLandlordIdOrderByIdAsc(chargeId, landlordId);
		if (all.size() == 1) {
			throw new LastItemException();
		}
		requirePositive(total(landlordId, chargeId) - item.getAmount());
		itemHistories.save(ChargeItemHistory.removed(item, actor(landlordId), clock.instant()));
		items.delete(item);
		items.flush();
		return finish(landlordId, charge, versionBefore);
	}

	/**
	 * 받을 월세를 취소한다. 입금 기록이 없을 때만(M3 결정 7). 취소한 달은 다시 만들 수 없다(M3 결정 8).
	 *
	 * @throws HasPaymentsException
	 *             받은 금액이 있을 때
	 * @throws ReasonInvalidException
	 *             사유가 비었거나 200자 초과
	 */
	public void cancel(long landlordId, long chargeId, long seenVersion, String reason) {
		Charge charge = changes.start(landlordId, chargeId, seenVersion);
		long versionBefore = charge.getVersion();
		if (charge.getPaidAmount() > 0 || charge.getStatus() == ChargeStatus.PAID) {
			throw new HasPaymentsException();
		}
		String trimmed = reason == null ? "" : reason.strip();
		if (trimmed.isEmpty() || trimmed.codePointCount(0, trimmed.length()) > ChargeStatusHistory.REASON_MAX_LENGTH) {
			throw new ReasonInvalidException();
		}
		Instant now = clock.instant();
		statusHistories
				.save(ChargeStatusHistory.of(charge, charge.cancel(trimmed, now), actor(landlordId), trimmed, now));
		changes.finish(charge, versionBefore);
	}

	/** 합계를 다시 계산하고, 상태가 바뀌면 이력을 남긴다 */
	private ChargeStatus finish(long landlordId, Charge charge, long versionBefore) {
		Instant now = clock.instant();
		charge.changeTotal(total(landlordId, charge.getId()), LocalDate.now(clock), now)
				.ifPresent(change -> statusHistories
						.save(ChargeStatusHistory.of(charge, change, actor(landlordId), REASON_ITEMS_CHANGED, now)));
		changes.finish(charge, versionBefore);
		return charge.getStatus();
	}

	private long total(long landlordId, long chargeId) {
		return items.findByChargeIdAndLandlordIdOrderByIdAsc(chargeId, landlordId).stream()
				.mapToLong(ChargeItem::getAmount).sum();
	}

	private ChargeItem item(long landlordId, long chargeId, long itemId) {
		return items.findByIdAndChargeIdAndLandlordId(itemId, chargeId, landlordId)
				.orElseThrow(() -> new ResourceNotFoundException("charge item"));
	}

	private static void requirePositive(long total) {
		if (total <= 0) {
			throw new ZeroTotalException();
		}
	}

	private static ChangeActor actor(long landlordId) {
		return ChangeActor.landlord(landlordId);
	}

}
