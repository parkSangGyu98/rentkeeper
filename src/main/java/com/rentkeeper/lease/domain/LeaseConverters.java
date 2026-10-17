package com.rentkeeper.lease.domain;

import com.rentkeeper.common.crypto.EncryptedConverter;
import com.rentkeeper.common.crypto.FieldEncryptor;

import jakarta.persistence.Converter;

/**
 * 세입자·계약의 개인정보 컬럼 암호화 컨버터 (ADR-017). 컬럼마다 용도(AAD)가 달라 암호문을 다른 컬럼에 옮겨 붙이면 복호화가
 * 실패한다. Hibernate가 Spring 빈 컨테이너로 만들어 FieldEncryptor를 주입한다.
 */
public final class LeaseConverters {

	private LeaseConverters() {
	}

	@Converter
	public static class TenantName extends EncryptedConverter<PersonName> {

		public TenantName(FieldEncryptor encryptor) {
			super(encryptor, "tenant.name");
		}

		@Override
		protected String toPlaintext(PersonName value) {
			return value.value();
		}

		@Override
		protected PersonName fromPlaintext(String plaintext) {
			return new PersonName(plaintext);
		}

	}

	@Converter
	public static class TenantPhone extends EncryptedConverter<PhoneNumber> {

		public TenantPhone(FieldEncryptor encryptor) {
			super(encryptor, "tenant.phone");
		}

		@Override
		protected String toPlaintext(PhoneNumber value) {
			return value.digits();
		}

		@Override
		protected PhoneNumber fromPlaintext(String plaintext) {
			return new PhoneNumber(plaintext);
		}

	}

	@Converter
	public static class TenantMemo extends EncryptedConverter<PrivateMemo> {

		public TenantMemo(FieldEncryptor encryptor) {
			super(encryptor, "tenant.memo");
		}

		@Override
		protected String toPlaintext(PrivateMemo value) {
			return value.value();
		}

		@Override
		protected PrivateMemo fromPlaintext(String plaintext) {
			return new PrivateMemo(plaintext);
		}

	}

	@Converter
	public static class AccountNo extends EncryptedConverter<AccountNumber> {

		public AccountNo(FieldEncryptor encryptor) {
			super(encryptor, "lease.account_no");
		}

		@Override
		protected String toPlaintext(AccountNumber value) {
			return value.digits();
		}

		@Override
		protected AccountNumber fromPlaintext(String plaintext) {
			return new AccountNumber(plaintext);
		}

	}

	@Converter
	public static class AccountHolder extends EncryptedConverter<PersonName> {

		public AccountHolder(FieldEncryptor encryptor) {
			super(encryptor, "lease.account_holder");
		}

		@Override
		protected String toPlaintext(PersonName value) {
			return value.value();
		}

		@Override
		protected PersonName fromPlaintext(String plaintext) {
			return new PersonName(plaintext);
		}

	}

}
