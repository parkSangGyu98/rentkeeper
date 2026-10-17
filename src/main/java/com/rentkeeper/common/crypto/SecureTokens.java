package com.rentkeeper.common.crypto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * 링크·쿠키에 넣는 무작위 토큰과 그 해시 (ADR-011).
 * <p>
 * 토큰은 256비트 무작위 값이라 비밀번호처럼 느린 해시(BCrypt)가 필요 없다. 같은 입력이 같은 SHA-256을 내므로 해시로 바로
 * 찾을 수 있고, DB가 유출돼도 해시만으로는 링크를 만들 수 없다.
 */
public final class SecureTokens {

	private static final SecureRandom RANDOM = new SecureRandom();

	private static final int TOKEN_BYTES = 32;

	// 32바이트 Base64URL(패딩 없음) = 43자
	private static final Pattern TOKEN_FORMAT = Pattern.compile("^[A-Za-z0-9_-]{43}$");

	private SecureTokens() {
	}

	/** 새 토큰 원문. 메일 링크나 쿠키에만 넣고 DB에는 {@link #sha256Hex(String)}만 저장한다 */
	public static String newToken() {
		byte[] bytes = new byte[TOKEN_BYTES];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	/** 사용자가 보낸 값이 이 클래스가 만든 토큰 형식인지. 형식이 다르면 DB를 조회할 필요도 없다 */
	public static boolean isWellFormed(String token) {
		return token != null && TOKEN_FORMAT.matcher(token).matches();
	}

	/** 소문자 hex 64자 */
	public static String sha256Hex(String value) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is required by the Java platform", e);
		}
	}

}
