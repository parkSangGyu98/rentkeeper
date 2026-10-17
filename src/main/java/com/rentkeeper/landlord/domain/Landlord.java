package com.rentkeeper.landlord.domain;

import java.util.Objects;

import com.rentkeeper.common.error.BusinessException;
import com.rentkeeper.common.error.ErrorCode;
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
 * 임대인 계정 (PRD 5.1 landlord). 인증 방식(비밀번호 해시 생성 등)은 auth 모듈이 맡고, 이 엔티티는 계정 데이터만
 * 가진다.
 */
@Entity
@Table(name = "landlord")
public class Landlord extends BaseTimeEntity {

	public static final int NAME_MAX_LENGTH = 50;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(length = EmailAddress.MAX_LENGTH)
	private String email;

	@Column(name = "password_hash")
	private String passwordHash;

	@Enumerated(EnumType.STRING)
	@Column(name = "auth_provider", nullable = false, length = 20)
	private AuthProvider authProvider;

	@Column(nullable = false, length = NAME_MAX_LENGTH)
	private String name;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private LandlordStatus status;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private Plan plan;

	protected Landlord() {
	}

	private Landlord(EmailAddress email, String passwordHash, AuthProvider authProvider, String name) {
		this.email = email.value();
		this.passwordHash = passwordHash;
		this.authProvider = authProvider;
		this.name = name;
		this.status = LandlordStatus.ACTIVE;
		this.plan = Plan.FREE;
	}

	/**
	 * 이메일 가입 계정을 만든다. 비밀번호는 이미 해시된 값을 받는다 (평문 비밀번호는 이 모듈로 들어오지 않는다).
	 */
	public static Landlord registerLocal(EmailAddress email, String passwordHash, String name) {
		Objects.requireNonNull(email, "email");
		if (passwordHash == null || passwordHash.isBlank()) {
			throw new IllegalArgumentException("passwordHash is required");
		}
		return new Landlord(email, passwordHash, AuthProvider.LOCAL, normalizeName(name));
	}

	static String normalizeName(String name) {
		String stripped = name == null ? "" : name.strip();
		if (stripped.isEmpty() || stripped.length() > NAME_MAX_LENGTH) {
			throw new InvalidNameException();
		}
		return stripped;
	}

	public Long getId() {
		return id;
	}

	public String getEmail() {
		return email;
	}

	public String getPasswordHash() {
		return passwordHash;
	}

	public AuthProvider getAuthProvider() {
		return authProvider;
	}

	public String getName() {
		return name;
	}

	public LandlordStatus getStatus() {
		return status;
	}

	public Plan getPlan() {
		return plan;
	}

	public static class InvalidNameException extends BusinessException {

		InvalidNameException() {
			super(ErrorCode.INVALID_NAME, "name must be 1-" + NAME_MAX_LENGTH + " characters");
		}

	}

}
