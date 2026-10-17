package com.rentkeeper.common.money;

import java.text.NumberFormat;
import java.util.Locale;

/**
 * 원화 금액 표시 (ADR-003: 금액은 원 단위 long). 화면에서만 쓴다. 40~60대 임대인이 읽기 쉽게 "500,000원"과 함께
 * "50만 원"처럼 만·억 단위도 보여 준다.
 */
public final class Won {

	private Won() {
	}

	/** 500000 → "500,000원" */
	public static String format(long amount) {
		return NumberFormat.getIntegerInstance(Locale.KOREA).format(amount) + "원";
	}

	/**
	 * 500000 → "50만 원", 120000000 → "1억 2,000만 원", 15000 → "1만 5,000원", 0 → "0원"
	 */
	public static String korean(long amount) {
		if (amount == 0) {
			return "0원";
		}
		NumberFormat number = NumberFormat.getIntegerInstance(Locale.KOREA);
		long eok = amount / 100_000_000L;
		long man = amount % 100_000_000L / 10_000L;
		long rest = amount % 10_000L;
		StringBuilder text = new StringBuilder();
		if (eok > 0) {
			text.append(number.format(eok)).append("억");
		}
		if (man > 0) {
			text.append(text.isEmpty() ? "" : " ").append(number.format(man)).append("만");
		}
		if (rest > 0) {
			text.append(text.isEmpty() ? "" : " ").append(number.format(rest)).append("원");
		} else {
			text.append(" 원");
		}
		return text.toString();
	}

	/**
	 * 입력 칸의 금액 읽기: 쉼표·공백·"원"을 지운다 ("500,000원" → 500000).
	 *
	 * @return 숫자가 아니거나 비었으면 null
	 */
	public static Long parse(String input) {
		if (input == null) {
			return null;
		}
		String digits = input.replaceAll("[,\\s원]", "");
		if (!digits.matches("[0-9]{1,15}")) {
			return null;
		}
		return Long.parseLong(digits);
	}

}
