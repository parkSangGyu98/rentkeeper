package com.rentkeeper.landlord.application;

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

	public LandlordService(LandlordRepository landlordRepository) {
		this.landlordRepository = landlordRepository;
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
