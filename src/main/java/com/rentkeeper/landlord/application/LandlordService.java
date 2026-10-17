package com.rentkeeper.landlord.application;

import java.time.Clock;
import java.util.Optional;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.rentkeeper.landlord.domain.AuthProvider;
import com.rentkeeper.landlord.domain.EmailAddress;
import com.rentkeeper.landlord.domain.Landlord;
import com.rentkeeper.landlord.domain.LandlordRepository;
import com.rentkeeper.landlord.domain.LandlordStatus;

/**
 * landlord 모듈의 공개 API. 다른 모듈(auth 등)은 repository나 엔티티 대신 이 서비스를 쓴다 (PRD 10장).
 */
@Service
@Transactional(readOnly = true)
public class LandlordService {

	static final String LOCAL_EMAIL_UNIQUE_CONSTRAINT = "uk_landlord_local_email";

	static final String KAKAO_ID_UNIQUE_CONSTRAINT = "uk_landlord_kakao_id";

	private final LandlordRepository landlordRepository;

	private final Clock clock;

	public LandlordService(LandlordRepository landlordRepository, Clock clock) {
		this.landlordRepository = landlordRepository;
		this.clock = clock;
	}

	/**
	 * 이메일 가입 계정을 만들고 id를 돌려준다.
	 *
	 * @throws DuplicateEmailException
	 *             같은 이메일의 이메일 가입 계정이 이미 있을 때
	 */
	@Transactional
	public long registerLocal(EmailAddress email, String passwordHash, String name) {
		if (landlordRepository.existsByAuthProviderAndEmailAndStatus(AuthProvider.LOCAL, email.value(),
				LandlordStatus.ACTIVE)) {
			throw new DuplicateEmailException();
		}
		try {
			// flush로 INSERT를 즉시 실행해 unique 위반을 이 메서드 안에서 잡는다
			return landlordRepository.saveAndFlush(Landlord.registerLocal(email, passwordHash, name)).getId();
		} catch (DataIntegrityViolationException e) {
			// 위의 존재 확인과 INSERT 사이에 같은 이메일 가입이 끼어든 경우. 다른 제약 위반은 그대로 올려 보낸다
			if (isLocalEmailUniqueViolation(e)) {
				throw new DuplicateEmailException(e);
			}
			throw e;
		}
	}

	/**
	 * 카카오 계정을 만들고 id를 돌려준다 (ADR-014).
	 *
	 * @param notificationEmail
	 *            알림 받을 이메일, 없으면 null
	 * @param emailTrusted
	 *            카카오가 인증한 이메일을 그대로 쓰는 경우 true → 인증된 것으로 저장
	 * @throws DuplicateKakaoAccountException
	 *             같은 카카오 회원번호의 사용 중인 계정이 이미 있을 때 (다른 창에서 동시에 가입한 경우 등)
	 */
	@Transactional
	public long registerKakao(String kakaoId, String name, EmailAddress notificationEmail, boolean emailTrusted) {
		Landlord landlord = Landlord.registerKakao(kakaoId, name, notificationEmail,
				notificationEmail != null && emailTrusted ? clock.instant() : null);
		try {
			return landlordRepository.saveAndFlush(landlord).getId();
		} catch (DataIntegrityViolationException e) {
			if (hasConstraint(e, KAKAO_ID_UNIQUE_CONSTRAINT)) {
				throw new DuplicateKakaoAccountException(e);
			}
			throw e;
		}
	}

	/** 카카오 계정의 아직 인증되지 않은 알림 이메일을 넣거나 고친다. 인증 메일은 호출한 쪽이 보낸다 */
	@Transactional
	public void setUnverifiedNotificationEmail(long landlordId, EmailAddress email) {
		landlordRepository.findById(landlordId).orElseThrow(() -> new IllegalArgumentException("landlord not found"))
				.setUnverifiedNotificationEmail(email);
	}

	/** 탈퇴 처리한다 (ADR-015). 이미 탈퇴했으면 IllegalStateException */
	@Transactional
	public void withdraw(long landlordId) {
		landlordRepository.findById(landlordId).orElseThrow(() -> new IllegalArgumentException("landlord not found"))
				.withdraw(clock.instant());
	}

	/** 탈퇴한 카카오 계정의 연결 끊기 성공을 기록한다 */
	@Transactional
	public void markKakaoUnlinked(long landlordId) {
		landlordRepository.findById(landlordId).orElseThrow(() -> new IllegalArgumentException("landlord not found"))
				.markKakaoUnlinked(clock.instant());
	}

	public Optional<LandlordCredentials> findLocalCredentials(EmailAddress email) {
		return landlordRepository
				.findByAuthProviderAndEmailAndStatus(AuthProvider.LOCAL, email.value(), LandlordStatus.ACTIVE)
				.map(landlord -> new LandlordCredentials(landlord.getId(), landlord.getEmail(),
						landlord.getPasswordHash(), landlord.getName(), landlord.getStatus()));
	}

	/** 사용 중(ACTIVE)인 카카오 계정을 회원번호로 찾는다. 탈퇴 유예 중인 계정은 찾지 않는다 */
	public Optional<LandlordAccount> findActiveKakaoAccount(String kakaoId) {
		return landlordRepository
				.findByAuthProviderAndKakaoIdAndStatus(AuthProvider.KAKAO, kakaoId, LandlordStatus.ACTIVE)
				.map(LandlordService::toAccount);
	}

	public Optional<LandlordAccount> findAccount(long landlordId) {
		return landlordRepository.findById(landlordId).map(LandlordService::toAccount);
	}

	/**
	 * 알림을 보낼 주소. <strong>인증된 이메일일 때만</strong> 돌려준다 (M1-b 결정: 알림은 인증된 주소로만). 인증·재설정
	 * 링크처럼 본인 확인이 목적인 메일 외에는 모두 이 메서드로 수신 주소를 얻는다.
	 */
	public Optional<EmailAddress> findVerifiedEmail(long landlordId) {
		return landlordRepository.findById(landlordId).filter(Landlord::isEmailVerified)
				.map(landlord -> new EmailAddress(landlord.getEmail()));
	}

	/**
	 * 인증 메일을 보낸 주소가 지금 계정 이메일과 같으면 인증 처리한다.
	 *
	 * @return 인증 처리했으면 true. 계정이 없거나 그 사이 이메일이 바뀌었으면 false
	 */
	@Transactional
	public boolean verifyEmail(long landlordId, EmailAddress verifiedAddress) {
		return landlordRepository.findById(landlordId)
				.map(landlord -> landlord.verifyEmail(verifiedAddress, clock.instant())).orElse(false);
	}

	@Transactional
	public void changePassword(long landlordId, String newPasswordHash) {
		landlordRepository.findById(landlordId).orElseThrow(() -> new IllegalArgumentException("landlord not found"))
				.changePassword(newPasswordHash);
	}

	public boolean isLocalEmailTaken(EmailAddress email) {
		return landlordRepository.existsByAuthProviderAndEmailAndStatus(AuthProvider.LOCAL, email.value(),
				LandlordStatus.ACTIVE);
	}

	/** 이메일 변경 결과: 이전 주소와 그 주소가 인증돼 있었는지 (이전 주소로 보안 안내를 보낼지 판단) */
	public record EmailChanged(String previousEmail, boolean previousVerified) {
	}

	/**
	 * 이메일을 바꾸고 인증된 것으로 표시한다 (새 주소의 확인 링크를 누른 뒤에만 호출).
	 *
	 * @throws DuplicateEmailException
	 *             그 사이 다른 이메일 가입 계정이 같은 주소를 쓰게 된 경우
	 */
	@Transactional
	public EmailChanged changeEmail(long landlordId, EmailAddress newEmail) {
		Landlord landlord = landlordRepository.findById(landlordId)
				.orElseThrow(() -> new IllegalArgumentException("landlord not found"));
		EmailChanged previous = new EmailChanged(landlord.getEmail(), landlord.isEmailVerified());
		landlord.changeEmail(newEmail, clock.instant());
		try {
			landlordRepository.saveAndFlush(landlord);
		} catch (DataIntegrityViolationException e) {
			if (isLocalEmailUniqueViolation(e)) {
				throw new DuplicateEmailException(e);
			}
			throw e;
		}
		return previous;
	}

	/** 지금 비밀번호 해시 (비밀번호 변경 화면의 "현재 비밀번호" 확인용, auth 모듈 전용) */
	public Optional<String> findPasswordHash(long landlordId) {
		return landlordRepository.findById(landlordId).map(Landlord::getPasswordHash);
	}

	private static LandlordAccount toAccount(Landlord landlord) {
		return new LandlordAccount(landlord.getId(), landlord.getEmail(), landlord.getName(),
				landlord.isEmailVerified(), landlord.getStatus(), landlord.getAuthProvider(), landlord.getKakaoId());
	}

	static boolean isLocalEmailUniqueViolation(Throwable e) {
		return hasConstraint(e, LOCAL_EMAIL_UNIQUE_CONSTRAINT);
	}

	private static boolean hasConstraint(Throwable e, String constraintName) {
		for (Throwable t = e; t != null; t = t.getCause()) {
			if (t instanceof ConstraintViolationException cve
					&& constraintName.equalsIgnoreCase(cve.getConstraintName())) {
				return true;
			}
		}
		return false;
	}

}
