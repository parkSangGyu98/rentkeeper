package com.rentkeeper.landlord.domain;

import java.time.Instant;
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

	// NULL이면 미인증. 알림은 인증된 주소로만 보낸다 (M1-b)
	@Column(name = "email_verified_at")
	private Instant emailVerifiedAt;

	// 카카오 회원번호. 카카오 계정만 있다 (M1-c)
	@Column(name = "kakao_id", length = 64)
	private String kakaoId;

	// 탈퇴 처리 시각. NULL이면 탈퇴 안 함. 30일 뒤 파기 (M1-c)
	@Column(name = "withdrawn_at")
	private Instant withdrawnAt;

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

	/**
	 * 카카오 계정을 만든다 (ADR-014). 비밀번호는 없다.
	 *
	 * @param notificationEmail
	 *            알림 받을 이메일. 없으면 null(나중에 입력)
	 * @param emailVerifiedAt
	 *            카카오가 인증한 이메일을 그대로 쓰면 그 시각, 아니면 null(우리 인증 메일로 확인)
	 */
	public static Landlord registerKakao(String kakaoId, String name, EmailAddress notificationEmail,
			Instant emailVerifiedAt) {
		if (kakaoId == null || !kakaoId.matches("[0-9]+")) {
			throw new IllegalArgumentException("kakaoId must be numeric");
		}
		if (notificationEmail == null && emailVerifiedAt != null) {
			throw new IllegalArgumentException("cannot be verified without email");
		}
		Landlord landlord = new Landlord();
		landlord.kakaoId = kakaoId;
		landlord.email = notificationEmail == null ? null : notificationEmail.value();
		landlord.emailVerifiedAt = emailVerifiedAt;
		landlord.authProvider = AuthProvider.KAKAO;
		landlord.name = normalizeName(name);
		landlord.status = LandlordStatus.ACTIVE;
		landlord.plan = Plan.FREE;
		return landlord;
	}

	/**
	 * 카카오 계정의 <strong>아직 인증되지 않은</strong> 알림 이메일을 넣거나 고친다 (M1-c 결정 11). 인증된 주소를 바꾸는
	 * 것은 P1이다. 이메일 가입 계정은 이메일이 로그인 ID라 이 방법으로 바꿀 수 없다 (재인증이 있는 이메일 변경을 쓴다).
	 */
	public void setUnverifiedNotificationEmail(EmailAddress newEmail) {
		if (authProvider != AuthProvider.KAKAO) {
			throw new IllegalStateException("only KAKAO accounts can set notification email this way");
		}
		if (emailVerifiedAt != null) {
			throw new IllegalStateException("verified email change is not supported yet (P1)");
		}
		this.email = Objects.requireNonNull(newEmail, "newEmail").value();
	}

	/**
	 * 지금 이메일이 인증 메일을 보낸 주소와 같을 때만 인증 처리한다. 인증 메일을 보낸 뒤 이메일이 바뀌었다면 예전 주소의 링크로 새 주소가
	 * 인증되면 안 된다.
	 *
	 * @return 인증 처리했으면(이미 인증된 경우 포함) true
	 */
	public boolean verifyEmail(EmailAddress verifiedAddress, Instant now) {
		if (email == null || !email.equals(verifiedAddress.value())) {
			return false;
		}
		if (emailVerifiedAt == null) {
			emailVerifiedAt = now;
		}
		return true;
	}

	public boolean isEmailVerified() {
		return emailVerifiedAt != null;
	}

	/**
	 * 이메일을 바꾼다. 새 주소로 보낸 확인 링크를 누른 뒤에만 호출되므로, 바꾸는 순간 인증된 것으로 본다 (M1-b 결정 7).
	 */
	public void changeEmail(EmailAddress newEmail, Instant verifiedAt) {
		Objects.requireNonNull(newEmail, "newEmail");
		this.email = newEmail.value();
		this.emailVerifiedAt = verifiedAt;
	}

	/** 비밀번호를 바꾼다. 이미 해시된 값을 받는다 (평문은 auth 모듈 밖으로 나오지 않는다) */
	public void changePassword(String newPasswordHash) {
		if (authProvider != AuthProvider.LOCAL) {
			throw new IllegalStateException("only LOCAL accounts have a password");
		}
		if (newPasswordHash == null || newPasswordHash.isBlank()) {
			throw new IllegalArgumentException("passwordHash is required");
		}
		this.passwordHash = newPasswordHash;
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

	public String getKakaoId() {
		return kakaoId;
	}

	public Instant getWithdrawnAt() {
		return withdrawnAt;
	}

	public static class InvalidNameException extends BusinessException {

		InvalidNameException() {
			super(ErrorCode.INVALID_NAME, "name must be 1-" + NAME_MAX_LENGTH + " characters");
		}

	}

}
