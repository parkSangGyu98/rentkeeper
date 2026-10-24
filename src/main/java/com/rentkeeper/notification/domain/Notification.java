package com.rentkeeper.notification.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

import com.rentkeeper.common.jpa.BaseTimeEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * 알림 한 건 (V19, ADR-028). 행은 {@code NotificationOutbox}가 INSERT … ON CONFLICT DO
 * NOTHING으로 넣고, 이 엔티티는 상태만 바꾼다. 전화번호·주소·본문·링크 원문은 담지 않는다: 받는 사람은 ID로만 가리킨다.
 */
@Entity
@Table(name = "notification")
public class Notification extends BaseTimeEntity {

	/** 실패하면 이만큼 기다렸다 다시 보낸다. 다 쓰면 FAILED (M5 결정 2) */
	public static final List<Duration> RETRY_DELAYS = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5),
			Duration.ofMinutes(15));

	/** 보내 볼 수 있는 횟수 (처음 + 재시도) */
	public static final int MAX_ATTEMPTS = RETRY_DELAYS.size();

	@Id
	private Long id;

	@Column(name = "landlord_id", updatable = false)
	private Long landlordId;

	@Enumerated(EnumType.STRING)
	@Column(name = "type", nullable = false, updatable = false)
	private NotificationType type;

	@Enumerated(EnumType.STRING)
	@Column(name = "channel", nullable = false, updatable = false)
	private NotificationChannel channel;

	@Enumerated(EnumType.STRING)
	@Column(name = "recipient_type", nullable = false, updatable = false)
	private RecipientType recipientType;

	@Column(name = "tenant_id", updatable = false)
	private Long tenantId;

	@Column(name = "lease_id", updatable = false)
	private Long leaseId;

	@Column(name = "charge_id", updatable = false)
	private Long chargeId;

	@Column(name = "ops_code", updatable = false)
	private String opsCode;

	@Column(name = "scheduled_for", nullable = false, updatable = false)
	private LocalDate scheduledFor;

	@Column(name = "dedupe_key", nullable = false, updatable = false)
	private String dedupeKey;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false)
	private NotificationStatus status;

	@Enumerated(EnumType.STRING)
	@Column(name = "skip_reason")
	private SkipReason skipReason;

	@Column(name = "attempts", nullable = false)
	private short attempts;

	@Column(name = "next_attempt_at", nullable = false)
	private Instant nextAttemptAt;

	@Column(name = "claimed_until")
	private Instant claimedUntil;

	@Column(name = "last_error")
	private String lastError;

	@Column(name = "sent_at")
	private Instant sentAt;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	protected Notification() {
	}

	/** 워커가 가져간다: 보내는 중으로 바꾸고 보내 본 횟수를 올린다. 잠금이 만료된 SENDING도 다시 가져갈 수 있다 */
	public void claim(Instant claimedUntil) {
		move(NotificationStatus.SENDING);
		this.claimedUntil = Objects.requireNonNull(claimedUntil, "claimedUntil");
		this.attempts++;
	}

	public void markSent(Instant now) {
		move(NotificationStatus.SENT);
		this.claimedUntil = null;
		this.sentAt = Objects.requireNonNull(now, "now");
	}

	/**
	 * 보내다 실패했다. 횟수가 남았으면 정해진 만큼 뒤로 미루고, 다 썼으면 FAILED.
	 *
	 * @param errorClass
	 *            예외 클래스 이름 (메시지는 개인정보·링크가 섞일 수 있어 받지 않는다)
	 * @return FAILED가 됐으면 true
	 */
	public boolean markAttemptFailed(Instant now, String errorClass) {
		this.lastError = errorClass == null ? null : errorClass.substring(0, Math.min(errorClass.length(), 200));
		this.claimedUntil = null;
		if (attempts >= MAX_ATTEMPTS) {
			move(NotificationStatus.FAILED);
			return true;
		}
		move(NotificationStatus.PENDING);
		this.nextAttemptAt = now.plus(RETRY_DELAYS.get(attempts - 1));
		return false;
	}

	public void skip(SkipReason reason) {
		move(NotificationStatus.SKIPPED);
		this.skipReason = Objects.requireNonNull(reason, "reason");
		this.claimedUntil = null;
	}

	/** 보낼 날(scheduled_for)의 발송 시간이 지났다 */
	public void expire() {
		move(NotificationStatus.EXPIRED);
		this.claimedUntil = null;
	}

	private void move(NotificationStatus next) {
		if (!status.canMoveTo(next)) {
			throw new NotificationStateException(status, next);
		}
		this.status = next;
	}

	public Long getId() {
		return id;
	}

	public Long getLandlordId() {
		return landlordId;
	}

	public NotificationType getType() {
		return type;
	}

	public NotificationChannel getChannel() {
		return channel;
	}

	public RecipientType getRecipientType() {
		return recipientType;
	}

	public Long getTenantId() {
		return tenantId;
	}

	public Long getLeaseId() {
		return leaseId;
	}

	public Long getChargeId() {
		return chargeId;
	}

	public String getOpsCode() {
		return opsCode;
	}

	public LocalDate getScheduledFor() {
		return scheduledFor;
	}

	public String getDedupeKey() {
		return dedupeKey;
	}

	public NotificationStatus getStatus() {
		return status;
	}

	public SkipReason getSkipReason() {
		return skipReason;
	}

	public int getAttempts() {
		return attempts;
	}

	public Instant getNextAttemptAt() {
		return nextAttemptAt;
	}

	public Instant getClaimedUntil() {
		return claimedUntil;
	}

	public String getLastError() {
		return lastError;
	}

	public Instant getSentAt() {
		return sentAt;
	}

	public long getVersion() {
		return version;
	}

	/** 받는 사람을 ID로만 적는다 (로그용, 예: TENANT:12) */
	public String recipientLabel() {
		return switch (recipientType) {
			case TENANT -> "TENANT:" + tenantId;
			case LANDLORD -> "LANDLORD:" + landlordId;
			case OPS -> "OPS:" + opsCode;
		};
	}

	@Override
	public String toString() {
		return "Notification[id=" + id + ", type=" + type + ", status=" + status + "]";
	}

	/** 단위 테스트용: DB 없이 PENDING 행을 만든다 */
	static Notification pendingForTest(NotificationType type, Instant nextAttemptAt, LocalDate scheduledFor) {
		Notification n = new Notification();
		n.type = type;
		n.channel = type.channel();
		n.recipientType = type.recipientType();
		n.status = NotificationStatus.PENDING;
		n.nextAttemptAt = nextAttemptAt;
		n.scheduledFor = scheduledFor;
		return n;
	}

}
