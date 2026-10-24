package com.rentkeeper.lease.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Optional;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.rentkeeper.common.jpa.BaseTimeEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 세입자 링크 토큰 (TEN-1, V17, ADR-026). 원문은 갖지 않고 SHA-256 해시만 갖는다. 열린 링크는 계약의 실제 끝 +
 * {@value #USABLE_DAYS_AFTER_END}일까지 쓸 수 있다(만료일은 저장하지 않고 {@link #usableUntil}로
 * 계산, M4 결정 4).
 */
@Entity
@Table(name = "tenant_access_token")
public class TenantAccessToken extends BaseTimeEntity {

	/** 계약이 끝난 뒤에도 링크를 쓸 수 있는 날 수 (PRD 4.7: 계약 종료일 + 90일) */
	public static final int USABLE_DAYS_AFTER_END = 90;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "landlord_id", nullable = false, updatable = false)
	private long landlordId;

	@Column(name = "lease_id", nullable = false, updatable = false)
	private long leaseId;

	// DB 타입이 char(64)라 ddl-auto=validate가 통과하도록 JDBC 타입을 CHAR로 맞춘다
	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(name = "token_hash", nullable = false, updatable = false, length = 64)
	private String tokenHash;

	@Column(name = "revoked_at")
	private Instant revokedAt;

	@Column(name = "last_used_at")
	private Instant lastUsedAt;

	protected TenantAccessToken() {
	}

	/**
	 * @param tokenHash
	 *            원문의 SHA-256 hex. 원문은 여기에 넘기지 않는다
	 */
	public static TenantAccessToken open(long landlordId, long leaseId, String tokenHash) {
		TenantAccessToken token = new TenantAccessToken();
		token.landlordId = landlordId;
		token.leaseId = leaseId;
		token.tokenHash = Objects.requireNonNull(tokenHash);
		return token;
	}

	/**
	 * 링크를 쓸 수 있는 마지막 날 (그날까지 열린다). 실제 끝은 종료했으면 끝난 날, 아니면 나가는 날이다. 둘 다 없으면 빈 값이고, 그
	 * 링크는 쓸 수 없는 것으로 본다(닫힌 쪽으로 실패, ADR-026).
	 */
	public static Optional<LocalDate> usableUntil(LocalDate endDate, LocalDate endedOn) {
		LocalDate actualEnd = endedOn != null ? endedOn : endDate;
		return Optional.ofNullable(actualEnd).map(end -> end.plusDays(USABLE_DAYS_AFTER_END));
	}

	/** 그날 링크를 쓸 수 있는지 (열려 있고 기간 안) */
	public boolean usableOn(LocalDate today, LocalDate endDate, LocalDate endedOn) {
		return isOpen() && usableUntil(endDate, endedOn).map(last -> !today.isAfter(last)).orElse(false);
	}

	public boolean isOpen() {
		return revokedAt == null;
	}

	/** 링크를 닫는다. 닫힌 링크는 다시 열지 않는다 */
	public void revoke(Instant now) {
		if (revokedAt != null) {
			throw new IllegalStateException("already revoked");
		}
		this.revokedAt = Objects.requireNonNull(now);
	}

	/**
	 * 세입자가 링크를 열었다고 기록한다. 쓰기를 줄이려고 같은 날(zone 기준) 이미 기록했으면 바꾸지 않는다.
	 *
	 * @return 바꿨으면 true
	 */
	public boolean markUsed(Instant now, ZoneId zone) {
		if (lastUsedAt != null && lastUsedAt.atZone(zone).toLocalDate().equals(now.atZone(zone).toLocalDate())) {
			return false;
		}
		this.lastUsedAt = now;
		return true;
	}

	public Long getId() {
		return id;
	}

	public long getLandlordId() {
		return landlordId;
	}

	public long getLeaseId() {
		return leaseId;
	}

	public Instant getRevokedAt() {
		return revokedAt;
	}

	public Instant getLastUsedAt() {
		return lastUsedAt;
	}

	// 해시도 로그에 남기지 않는다
	@Override
	public String toString() {
		return "TenantAccessToken[id=" + id + ", leaseId=" + leaseId + ", open=" + isOpen() + "]";
	}

}
