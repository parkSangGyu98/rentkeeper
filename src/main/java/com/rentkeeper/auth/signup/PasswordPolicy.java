package com.rentkeeper.auth.signup;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.rentkeeper.landlord.domain.EmailAddress;

/**
 * 비밀번호 규칙 (ADR-009). NIST SP 800-63B 방식: 길이와 "흔한 비밀번호 차단"을 보고, 대문자·특수문자 조합은
 * 강제하지 않는다.
 * <ul>
 * <li>8자 이상 (PRD AUTH-1)</li>
 * <li>UTF-8 72바이트 이하: BCrypt는 72바이트까지만 쓰고, Spring Security 7은 그보다 길면 예외를 던진다.
 * 한글은 한 글자에 3바이트라 24자까지 된다</li>
 * <li>흔한 비밀번호 목록(auth/common-passwords.txt)에 없을 것</li>
 * <li>이메일 주소 또는 이메일 @ 앞부분과 같지 않을 것</li>
 * <li>한 글자만 반복하지 않을 것 (예: aaaaaaaa)</li>
 * </ul>
 */
@Component
public class PasswordPolicy {

	public static final int MIN_LENGTH = 8;

	public static final int MAX_BYTES = 72;

	private static final String COMMON_PASSWORDS = "/auth/common-passwords.txt";

	private final Set<String> commonPasswords;

	public PasswordPolicy() {
		this(loadCommonPasswords());
	}

	PasswordPolicy(Set<String> commonPasswords) {
		this.commonPasswords = Set.copyOf(commonPasswords);
	}

	public enum Violation {

		TOO_SHORT, TOO_LONG, TOO_COMMON, SAME_AS_EMAIL, REPEATED_CHARACTER

	}

	/**
	 * @return 규칙 위반이 있으면 그 이유, 없으면 빈 값
	 */
	public Optional<Violation> check(String password, EmailAddress email) {
		if (password == null || password.codePointCount(0, password.length()) < MIN_LENGTH) {
			return Optional.of(Violation.TOO_SHORT);
		}
		if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
			return Optional.of(Violation.TOO_LONG);
		}
		String lower = password.toLowerCase(Locale.ROOT);
		if (commonPasswords.contains(lower)) {
			return Optional.of(Violation.TOO_COMMON);
		}
		if (email != null) {
			String address = email.value();
			if (lower.equals(address) || lower.equals(address.substring(0, address.indexOf('@')))) {
				return Optional.of(Violation.SAME_AS_EMAIL);
			}
		}
		if (password.codePoints().distinct().count() == 1) {
			return Optional.of(Violation.REPEATED_CHARACTER);
		}
		return Optional.empty();
	}

	int commonPasswordCount() {
		return commonPasswords.size();
	}

	private static Set<String> loadCommonPasswords() {
		try (InputStream in = PasswordPolicy.class.getResourceAsStream(COMMON_PASSWORDS)) {
			if (in == null) {
				throw new IllegalStateException(COMMON_PASSWORDS + " not found");
			}
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
				return reader.lines().map(String::strip).filter(line -> !line.isEmpty() && !line.startsWith("#"))
						.map(line -> line.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

}
