package com.rentkeeper.consent;

import java.time.Instant;

import com.rentkeeper.site.LegalDocument;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 약관·개인정보처리방침 동의 기록 한 건 (ADR-016). 고치지 않는 기록이라 setter도, 수정 시각도 없다.
 */
@Entity
@Table(name = "consent_log")
public class ConsentLog {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "landlord_id", nullable = false)
	private long landlordId;

	@Enumerated(EnumType.STRING)
	@Column(name = "document_type", nullable = false, length = 20)
	private LegalDocument documentType;

	@Column(name = "document_version", nullable = false, length = 20)
	private String documentVersion;

	@Column(name = "agreed_at", nullable = false)
	private Instant agreedAt;

	protected ConsentLog() {
	}

	ConsentLog(long landlordId, LegalDocument document, Instant agreedAt) {
		this.landlordId = landlordId;
		this.documentType = document;
		this.documentVersion = document.getVersion();
		this.agreedAt = agreedAt;
	}

	public long getLandlordId() {
		return landlordId;
	}

	public LegalDocument getDocumentType() {
		return documentType;
	}

	public String getDocumentVersion() {
		return documentVersion;
	}

	public Instant getAgreedAt() {
		return agreedAt;
	}

}
