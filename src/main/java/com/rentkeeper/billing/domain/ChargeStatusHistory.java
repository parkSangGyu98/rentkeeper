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
 * 청구 상태 이력 한 건 (PRD 4.4.2, V16). 고치지 않는 기록이라 setter도, 수정 시각도 없다(ConsentLog와 같은
 * 예외).
 */
@Entity
@Table(name = "charge_status_history")
public class ChargeStatusHistory {

	public static final int REASON_MAX_LENGTH = 200;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "landlord_id", nullable = false, updatable = false)
	private long landlordId;

	@Column(name = "charge_id", nullable = false, updatable = false)
	private long chargeId;

	@Enumerated(EnumType.STRING)
	@Column(name = "from_status", length = 20, updatable = false)
	private ChargeStatus fromStatus;

	@Enumerated(EnumType.STRING)
	@Column(name = "to_status", nullable = false, length = 20, updatable = false)
	private ChargeStatus toStatus;

	@Enumerated(EnumType.STRING)
	@Column(name = "actor_type", nullable = false, length = 10, updatable = false)
	private ChangeActor.Type actorType;

	@Column(name = "actor_id", updatable = false)
	private Long actorId;

	@Column(length = REASON_MAX_LENGTH, updatable = false)
	private String reason;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected ChargeStatusHistory() {
	}

	/**
	 * @param reason
	 *            사유 (없으면 null). 개인정보를 넣지 않는다
	 */
	public static ChargeStatusHistory of(Charge charge, StatusChange change, ChangeActor actor, String reason,
			Instant now) {
		Objects.requireNonNull(charge.getId(), "charge must be saved first");
		ChargeStatusHistory history = new ChargeStatusHistory();
		history.landlordId = charge.getLandlordId();
		history.chargeId = charge.getId();
		history.fromStatus = change.from();
		history.toStatus = Objects.requireNonNull(change.to());
		history.actorType = actor.type();
		history.actorId = actor.id();
		history.reason = reason;
		history.createdAt = Objects.requireNonNull(now);
		return history;
	}

	public Long getId() {
		return id;
	}

	public long getChargeId() {
		return chargeId;
	}

	public ChargeStatus getFromStatus() {
		return fromStatus;
	}

	public ChargeStatus getToStatus() {
		return toStatus;
	}

	public ChangeActor.Type getActorType() {
		return actorType;
	}

	public Long getActorId() {
		return actorId;
	}

	public String getReason() {
		return reason;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
