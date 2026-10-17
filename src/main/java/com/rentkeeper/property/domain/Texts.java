package com.rentkeeper.property.domain;

/**
 * 이름·주소 입력 정리: 앞뒤 공백을 없애고 안쪽 연속 공백을 하나로 줄인다. "101 호"와 "101 호"가 다른 호실로 저장되지 않게
 * 한다.
 */
public final class Texts {

	private Texts() {
	}

	/** @return 정리한 값. 비었으면 null */
	public static String normalize(String value) {
		if (value == null) {
			return null;
		}
		String normalized = value.strip().replaceAll("\\s+", " ");
		return normalized.isEmpty() ? null : normalized;
	}

	/** 필수 입력: 정리한 값이 1~max자(코드 포인트)여야 한다 */
	static String required(String value, int max, String field) {
		String normalized = normalize(value);
		if (normalized == null || normalized.codePointCount(0, normalized.length()) > max) {
			throw new IllegalArgumentException(field + " must be 1-" + max + " characters");
		}
		return normalized;
	}

	/** 선택 입력: 비면 null, 있으면 max자 이하 */
	static String optional(String value, int max, String field) {
		String normalized = normalize(value);
		if (normalized != null && normalized.codePointCount(0, normalized.length()) > max) {
			throw new IllegalArgumentException(field + " must be at most " + max + " characters");
		}
		return normalized;
	}

	/** 메모: 줄바꿈은 살리고 앞뒤 공백만 없앤다 */
	static String memo(String value, int max) {
		if (value == null || value.isBlank()) {
			return null;
		}
		String stripped = value.strip();
		if (stripped.codePointCount(0, stripped.length()) > max) {
			throw new IllegalArgumentException("memo must be at most " + max + " characters");
		}
		return stripped;
	}

}
