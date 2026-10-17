package com.rentkeeper.landlord.domain;

import java.util.Locale;
import java.util.regex.Pattern;

import com.rentkeeper.common.error.BusinessException;
import com.rentkeeper.common.error.ErrorCode;

/**
 * 정규화된 이메일 주소. 이 타입의 값은 항상 앞뒤 공백이 없고 소문자다 (ADR-006).
 * <p>
 * 가입·로그인 모두 {@link #of(String)}를 거치므로 {@code User@Example.com}과
 * {@code user@example.com}은 같은 계정으로 취급된다. 국제화 이메일(한글 주소 등)은 받지 않는다. Java와
 * PostgreSQL의 소문자 변환 규칙이 비ASCII 문자에서 달라질 수 있어, DB의
 * {@code ck_landlord_email_normalized} 제약과 어긋나지 않도록 ASCII로 제한한다.
 */
public record EmailAddress(String value) {

	public static final int MAX_LENGTH = 254;

	// RFC 전체를 구현하지 않는다. 흔한 주소를 받아들이고 명백히 잘못된 입력만 거른다
	private static final Pattern FORMAT = Pattern.compile(
			"^[a-z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+$");

	public EmailAddress {
		if (value == null || value.length() > MAX_LENGTH || !FORMAT.matcher(value).matches()) {
			throw new InvalidEmailException();
		}
	}

	/**
	 * 사용자 입력을 정규화해 만든다. 소문자 변환은 {@link Locale#ROOT}로 한다 (터키어 로케일에서 'I'가 'ı'로 바뀌는 문제
	 * 방지).
	 */
	public static EmailAddress of(String raw) {
		if (raw == null) {
			throw new InvalidEmailException();
		}
		return new EmailAddress(raw.strip().toLowerCase(Locale.ROOT));
	}

	@Override
	public String toString() {
		return value;
	}

	public static class InvalidEmailException extends BusinessException {

		InvalidEmailException() {
			super(ErrorCode.INVALID_EMAIL, "invalid email address");
		}

	}

}
