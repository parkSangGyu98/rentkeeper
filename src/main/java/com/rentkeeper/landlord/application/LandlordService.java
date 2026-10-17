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

/**
 * landlord 모듈의 공개 API. 다른 모듈(auth 등)은 repository나 엔티티 대신 이 서비스를 쓴다 (PRD 10장).
 */
@Service
@Transactional(readOnly = true)
public class LandlordService {

	static final String LOCAL_EMAIL_UNIQUE_CONSTRAINT = "uk_landlord_local_email";

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
		if (landlordRepository.existsByAuthProviderAndEmail(AuthProvider.LOCAL, email.value())) {
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

	public Optional<LandlordCredentials> findLocalCredentials(EmailAddress email) {
		return landlordRepository.findByAuthProviderAndEmail(AuthProvider.LOCAL, email.value())
				.map(landlord -> new LandlordCredentials(landlord.getId(), landlord.getEmail(),
						landlord.getPasswordHash(), landlord.getName(), landlord.getStatus()));
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

	/** 지금 비밀번호 해시 (비밀번호 변경 화면의 "현재 비밀번호" 확인용, auth 모듈 전용) */
	public Optional<String> findPasswordHash(long landlordId) {
		return landlordRepository.findById(landlordId).map(Landlord::getPasswordHash);
	}

	private static LandlordAccount toAccount(Landlord landlord) {
		return new LandlordAccount(landlord.getId(), landlord.getEmail(), landlord.getName(),
				landlord.isEmailVerified(), landlord.getStatus());
	}

	static boolean isLocalEmailUniqueViolation(Throwable e) {
		for (Throwable t = e; t != null; t = t.getCause()) {
			if (t instanceof ConstraintViolationException cve
					&& LOCAL_EMAIL_UNIQUE_CONSTRAINT.equalsIgnoreCase(cve.getConstraintName())) {
				return true;
			}
		}
		return false;
	}

}
