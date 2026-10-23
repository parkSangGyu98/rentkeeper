package com.rentkeeper.billing.domain;

import java.time.Instant;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 청구 항목 변경 이력 한 건 (M3 결정 12, V16): 누가·언제 항목을 추가·변경·삭제했고 금액이 얼마에서 얼마로 바뀌었는지. 고치지
 * 않는 기록이다. 항목을 지워도 남도록 항목 id에 FK가 없다.
 */
@Entity
@Table(name = "charge_item_history")
public class ChargeItemHistory {

	public enum Action {
		ADD, CHANGE, REMOVE
	}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "landlord_id", nullable = false, updatable = false)
	private long landlordId;

	@Column(name = "charge_id", nullable = false, updatable = false)
	private long chargeId;

	@Column(name = "charge_item_id", nullable = false, updatable = false)
	private long chargeItemId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10, updatable = false)
	private Action action;

	@Enumerated(EnumType.STRING)
	@Column(name = "item_type", nullable = false, length = 20, updatable = false)
	private ChargeItemType itemType;

	@Column(nullable = false, length = ChargeItem.LABEL_MAX_LENGTH, updatable = false)
	private String label;

	@Column(name = "before_amount", updatable = false)
	private Long beforeAmount;

	@Column(name = "after_amount", updatable = false)
	private Long afterAmount;

	@Enumerated(EnumType.STRING)
	@Column(name = "actor_type", nullable = false, length = 10, updatable = false)
	private ChangeActor.Type actorType;

	@Column(name = "actor_id", updatable = false)
	private Long actorId;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected ChargeItemHistory() {
	}

	/** 항목을 추가함 (배치가 만든 자동 항목 포함) */
	public static ChargeItemHistory added(ChargeItem item, ChangeActor actor, Instant now) {
		return of(item, Action.ADD, null, item.getAmount(), actor, now);
	}

	/** 항목의 이름·금액을 고침 (고치기 전 금액을 함께 남긴다) */
	public static ChargeItemHistory changed(ChargeItem item, long beforeAmount, ChangeActor actor, Instant now) {
		return of(item, Action.CHANGE, beforeAmount, item.getAmount(), actor, now);
	}

	/** 항목을 지움 (지우기 전 금액을 남긴다) */
	public static ChargeItemHistory removed(ChargeItem item, ChangeActor actor, Instant now) {
		return of(item, Action.REMOVE, item.getAmount(), null, actor, now);
	}

	private static ChargeItemHistory of(ChargeItem item, Action action, Long before, Long after, ChangeActor actor,
			Instant now) {
		Objects.requireNonNull(item.getId(), "item must be saved first");
		if (actor.type() == ChangeActor.Type.TENANT) {
			throw new IllegalArgumentException("tenants do not change items");
		}
		ChargeItemHistory history = new ChargeItemHistory();
		history.landlordId = item.getLandlordId();
		history.chargeId = item.getChargeId();
		history.chargeItemId = item.getId();
		history.action = action;
		history.itemType = item.getType();
		history.label = item.getLabel();
		history.beforeAmount = before;
		history.afterAmount = after;
		history.actorType = actor.type();
		history.actorId = actor.id();
		history.createdAt = Objects.requireNonNull(now);
		return history;
	}

	public long getChargeItemId() {
		return chargeItemId;
	}

	public Action getAction() {
		return action;
	}

	public ChargeItemType getItemType() {
		return itemType;
	}

	public String getLabel() {
		return label;
	}

	public Long getBeforeAmount() {
		return beforeAmount;
	}

	public Long getAfterAmount() {
		return afterAmount;
	}

	public ChangeActor.Type getActorType() {
		return actorType;
	}

	public Long getActorId() {
		return actorId;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
