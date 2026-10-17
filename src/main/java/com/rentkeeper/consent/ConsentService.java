package com.rentkeeper.consent;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.rentkeeper.site.LegalDocument;

/**
 * 동의 기록 (AUTH-6, ADR-016). 가입할 때 이용약관·개인정보처리방침의 그 시점 버전에 동의한 사실을 남긴다.
 */
@Service
@Transactional(readOnly = true)
public class ConsentService {

	private final ConsentLogRepository repository;

	private final Clock clock;

	public ConsentService(ConsentLogRepository repository, Clock clock) {
		this.repository = repository;
		this.clock = clock;
	}

	/**
	 * 가입 때 필수 동의(이용약관 + 개인정보처리방침)를 기록한다. 계정 생성과 같은 트랜잭션에서 호출해야 한다: 동의 기록 없이 계정만
	 * 생기거나, 계정 없이 기록만 남는 일이 없게 하기 위해서다.
	 */
	@Transactional
	public void recordSignupAgreements(long landlordId) {
		Instant now = clock.instant();
		repository.saveAll(List.of(new ConsentLog(landlordId, LegalDocument.TERMS, now),
				new ConsentLog(landlordId, LegalDocument.PRIVACY, now)));
	}

	public List<ConsentLog> findByLandlord(long landlordId) {
		return repository.findByLandlordIdOrderByAgreedAtAsc(landlordId);
	}

}
