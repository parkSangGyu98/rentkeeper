package com.rentkeeper.common.crypto;

/**
 * 로그·toString()용 개인정보 마스킹 (PRD 5.2). 화면에는 평문을 보여 주고, 로그와 예외에는 이 형태만 남긴다.
 */
public final class Masking {

	private Masking() {
	}

	/** 김철수 → 김*수, 김철 → 김*, 남궁민수 → 남**수 */
	public static String name(String name) {
		if (name == null || name.isEmpty()) {
			return "";
		}
		int[] codePoints = name.codePoints().toArray();
		if (codePoints.length == 1) {
			return "*";
		}
		StringBuilder masked = new StringBuilder().appendCodePoint(codePoints[0]);
		int hidden = codePoints.length == 2 ? 1 : codePoints.length - 2;
		masked.append("*".repeat(hidden));
		if (codePoints.length > 2) {
			masked.appendCodePoint(codePoints[codePoints.length - 1]);
		}
		return masked.toString();
	}

	/** 01012345678 → 010-****-5678 */
	public static String phone(String digits) {
		if (digits == null || digits.length() < 7) {
			return "***";
		}
		return digits.substring(0, 3) + "-****-" + digits.substring(digits.length() - 4);
	}

	/** 마지막 4자리만: 12345678901 → ****8901 */
	public static String account(String account) {
		if (account == null || account.length() <= 4) {
			return "****";
		}
		return "****" + account.substring(account.length() - 4);
	}

	/** 메모 같은 자유 입력: 길이만 */
	public static String text(String text) {
		return text == null ? "null" : "[" + text.length() + "자]";
	}

}
