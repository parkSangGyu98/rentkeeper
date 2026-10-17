package com.rentkeeper.common.crypto;

import jakarta.persistence.AttributeConverter;

/**
 * 개인정보 컬럼을 투명하게 암호화하는 JPA 컨버터의 바탕 (ADR-017). 컬럼마다 하위 클래스를 만들어 용도(AAD)를 정한다.
 * <p>
 * 하위 클래스는 {@link FieldEncryptor}를 생성자로 받는다. Hibernate가 Spring 빈 컨테이너로 컨버터를 만들 때
 * 주입된다 (EncryptedConverterInjectionTests가 확인).
 *
 * @param <T>
 *            엔티티 필드 타입. 로그에 찍혀도 평문이 나가지 않게 toString()을 마스킹한 값 타입을 권장한다
 */
public abstract class EncryptedConverter<T> implements AttributeConverter<T, String> {

	private final FieldEncryptor encryptor;

	private final String purpose;

	protected EncryptedConverter(FieldEncryptor encryptor, String purpose) {
		this.encryptor = encryptor;
		this.purpose = purpose;
	}

	/** 저장할 평문 */
	protected abstract String toPlaintext(T value);

	/** 복호화한 평문으로 값 만들기 */
	protected abstract T fromPlaintext(String plaintext);

	@Override
	public String convertToDatabaseColumn(T attribute) {
		return attribute == null ? null : encryptor.encrypt(toPlaintext(attribute), purpose);
	}

	@Override
	public T convertToEntityAttribute(String dbData) {
		return dbData == null ? null : fromPlaintext(encryptor.decrypt(dbData, purpose));
	}

}
