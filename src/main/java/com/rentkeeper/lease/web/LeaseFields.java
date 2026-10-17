package com.rentkeeper.lease.web;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;

import org.springframework.validation.BindingResult;

import com.rentkeeper.common.money.Won;
import com.rentkeeper.lease.domain.AccountNumber;
import com.rentkeeper.lease.domain.InvalidLeaseValueException;
import com.rentkeeper.lease.domain.LeaseRentHistory;
import com.rentkeeper.lease.domain.LeaseTerms;
import com.rentkeeper.lease.domain.PersonName;
import com.rentkeeper.lease.domain.PhoneNumber;
import com.rentkeeper.lease.domain.PrivateMemo;

/**
 * 계약 폼(등록·수정·월세 변경)의 칸 검증. 칸마다 알아보기 쉬운 안내 문구를 넣고 입력값은 넣지 않는다. 틀리면 null을 돌려주고
 * result에 오류를 넣는다.
 */
final class LeaseFields {

	private LeaseFields() {
	}

	static PersonName tenantName(BindingResult result, String raw) {
		return parse(result, "tenantName", "세입자 이름을 1~50자로 입력해 주세요", () -> new PersonName(raw));
	}

	static PhoneNumber phone(BindingResult result, String raw) {
		return parse(result, "tenantPhone", "휴대폰 번호를 다시 확인해 주세요 (예: 010-1234-5678)", () -> PhoneNumber.parse(raw));
	}

	static PrivateMemo tenantMemo(BindingResult result, String raw) {
		return parse(result, "tenantMemo", "메모는 500자 이하로 입력해 주세요", () -> PrivateMemo.ofNullable(raw));
	}

	static Long deposit(BindingResult result, String raw) {
		return amount(result, "deposit", raw, false, LeaseTerms.DEPOSIT_MAX, "보증금을 숫자로 입력해 주세요 (없으면 0)",
				"보증금은 100억 원 이하로 입력해 주세요");
	}

	static Long rent(BindingResult result, String field, String raw) {
		return amount(result, field, raw, false, LeaseRentHistory.AMOUNT_MAX, "월세를 숫자로 입력해 주세요 (예: 500000)",
				"월세는 1억 원 이하로 입력해 주세요");
	}

	static Long fee(BindingResult result, String field, String raw) {
		return amount(result, field, raw, true, LeaseRentHistory.AMOUNT_MAX, "관리비를 숫자로 입력해 주세요 (없으면 비워 두세요)",
				"관리비는 1억 원 이하로 입력해 주세요");
	}

	static Integer dueDay(BindingResult result, String raw) {
		Integer day = raw == null || !raw.strip().matches("[0-9]{1,2}") ? null : Integer.valueOf(raw.strip());
		if (day == null || day < 1 || day > 31) {
			result.rejectValue("dueDay", "invalid", "매달 받는 날을 1~31일 중에서 골라 주세요");
			return null;
		}
		return day;
	}

	static LocalDate date(BindingResult result, String field, String raw, String message) {
		try {
			if (raw == null || raw.isBlank()) {
				result.rejectValue(field, "invalid", message);
				return null;
			}
			return LocalDate.parse(raw.strip());
		} catch (DateTimeParseException e) {
			result.rejectValue(field, "invalid", message);
			return null;
		}
	}

	/** "2027-03" 형식의 달 */
	static YearMonth month(BindingResult result, String field, String raw) {
		try {
			return YearMonth.parse(raw == null ? "" : raw.strip());
		} catch (DateTimeParseException e) {
			result.rejectValue(field, "invalid", "적용할 달을 골라 주세요");
			return null;
		}
	}

	static String bankName(BindingResult result, String raw) {
		String bank = raw == null ? "" : raw.strip();
		if (bank.isEmpty() || bank.codePointCount(0, bank.length()) > LeaseTerms.BANK_NAME_MAX_LENGTH) {
			result.rejectValue("bankName", "invalid", "은행 이름을 1~50자로 입력해 주세요 (예: 국민은행)");
			return null;
		}
		return bank;
	}

	static AccountNumber account(BindingResult result, String raw) {
		return parse(result, "accountNumber", "계좌번호를 숫자로 입력해 주세요 (6~30자리, - 는 빼도 돼요)", () -> AccountNumber.parse(raw));
	}

	static PersonName accountHolder(BindingResult result, String raw) {
		return parse(result, "accountHolder", "예금주 이름을 1~50자로 입력해 주세요", () -> new PersonName(raw));
	}

	static String plainMemo(BindingResult result, String field, String raw, int max) {
		String memo = raw == null ? "" : raw.strip();
		if (memo.codePointCount(0, memo.length()) > max) {
			result.rejectValue(field, "tooLong", (max == 200 ? "사유는 200자" : "메모는 500자") + " 이하로 입력해 주세요");
			return null;
		}
		return memo;
	}

	private interface Parser<T> {

		T parse();

	}

	private static <T> T parse(BindingResult result, String field, String message, Parser<T> parser) {
		try {
			return parser.parse();
		} catch (InvalidLeaseValueException e) {
			result.rejectValue(field, "invalid", message);
			return null;
		}
	}

	private static Long amount(BindingResult result, String field, String raw, boolean blankIsZero, long max,
			String invalidMessage, String tooLargeMessage) {
		if (blankIsZero && (raw == null || raw.isBlank())) {
			return 0L;
		}
		Long value = Won.parse(raw);
		if (value == null) {
			result.rejectValue(field, "invalid", invalidMessage);
			return null;
		}
		if (value > max) {
			result.rejectValue(field, "tooLarge", tooLargeMessage);
			return null;
		}
		return value;
	}

}
