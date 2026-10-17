package com.rentkeeper.auth.token;

import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.rentkeeper.common.jpa.BaseTimeEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 1회용 계정 토큰 (V4 account_token). 원문은 갖지 않고 해시만 갖는다 (ADR-011).
 * <p>
 * landlord는 다른 모듈의 엔티티라 JPA 연관관계 대신 id만 갖는다 (모듈 간 참조는 서비스로만, PRD 10장).
 */
@Entity
@Table(name = "account_token")
public class AccountToken extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "landlord_id", nullable = false)
	private long landlordId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private TokenPurpose purpose;

	// DB 타입이 char(64)라 ddl-auto=validate가 통과하도록 JDBC 타입을 CHAR로 맞춘다
	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(name = "token_hash", nullable = false, length = 64)
	private String tokenHash;

	@Column(name = "target_email", length = 254)
	private String targetEmail;

	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;

	@Column(name = "used_at")
	private Instant usedAt;

	protected AccountToken() {
	}

	AccountToken(long landlordId, TokenPurpose purpose, String tokenHash, String targetEmail, Instant expiresAt) {
		if (purpose.requiresTargetEmail() != (targetEmail != null)) {
			throw new IllegalArgumentException("targetEmail must be given only for " + purpose);
		}
		this.landlordId = landlordId;
		this.purpose = purpose;
		this.tokenHash = tokenHash;
		this.targetEmail = targetEmail;
		this.expiresAt = expiresAt;
	}

	public boolean isUsableAt(Instant now) {
		return usedAt == null && now.isBefore(expiresAt);
	}

	public Long getId() {
		return id;
	}

	public long getLandlordId() {
		return landlordId;
	}

	public TokenPurpose getPurpose() {
		return purpose;
	}

	public String getTargetEmail() {
		return targetEmail;
	}

	public Instant getExpiresAt() {
		return expiresAt;
	}

}
