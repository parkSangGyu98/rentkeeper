package com.rentkeeper.lease.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.rentkeeper.common.money.Won;

/**
 * 계약 입력 값 타입: 정규화, 검증, 마스킹된 toString(로그에 평문이 나가지 않게), 금액 표시.
 */
class LeaseValuesTest {

	@Test
	void phoneIsNormalizedValidatedAndMasked() {
		PhoneNumber phone = PhoneNumber.parse("010-1234-5678");

		assertThat(phone.digits()).isEqualTo("01012345678");
		assertThat(phone.formatted()).isEqualTo("010-1234-5678");
		assertThat(PhoneNumber.parse("011 123 4567").formatted()).isEqualTo("011-123-4567");
		assertThat(phone.toString()).isEqualTo("010-****-5678").doesNotContain("1234");
		for (String bad : new String[]{"02-123-4567", "0101234567a", "010123", "", null}) {
			assertThatThrownBy(() -> PhoneNumber.parse(bad)).as(String.valueOf(bad))
					.isInstanceOf(InvalidLeaseValueException.class).hasMessageNotContaining("02-123");
		}
	}

	@Test
	void namesAccountsAndMemosValidateByCodePointsAndMask() {
		assertThat(new PersonName("  홍   길동 ").value()).isEqualTo("홍 길동");
		assertThat(new PersonName("홍길동").toString()).isEqualTo("홍*동");
		assertThat(new PersonName("😀".repeat(50)).value()).hasSize(100);
		assertThatThrownBy(() -> new PersonName("😀".repeat(51))).isInstanceOf(InvalidLeaseValueException.class);
		assertThatThrownBy(() -> new PersonName(" ")).isInstanceOf(InvalidLeaseValueException.class);

		assertThat(AccountNumber.parse("123-456-789012").digits()).isEqualTo("123456789012");
		assertThat(AccountNumber.parse("123-456-789012").toString()).isEqualTo("****9012");
		assertThatThrownBy(() -> AccountNumber.parse("12345")).isInstanceOf(InvalidLeaseValueException.class);
		assertThatThrownBy(() -> AccountNumber.parse("1".repeat(31))).isInstanceOf(InvalidLeaseValueException.class);

		assertThat(PrivateMemo.ofNullable("  ")).isNull();
		assertThat(PrivateMemo.ofNullable("😀".repeat(500)).toString()).isEqualTo("[1000자]");
		assertThatThrownBy(() -> PrivateMemo.ofNullable("😀".repeat(501)))
				.isInstanceOf(InvalidLeaseValueException.class);
	}

	@Test
	void termsEnforceRanges() {
		AccountNumber account = new AccountNumber("1234567890");
		PersonName holder = new PersonName("김임대");
		LocalDate start = LocalDate.of(2026, 1, 1);

		assertThatThrownBy(() -> new LeaseTerms(-1, 1, start, start.plusYears(1), "국민", account, holder, null))
				.hasMessageContaining("deposit");
		assertThatThrownBy(() -> new LeaseTerms(LeaseTerms.DEPOSIT_MAX + 1, 1, start, start.plusYears(1), "국민", account,
				holder, null)).hasMessageContaining("deposit");
		assertThatThrownBy(() -> new LeaseTerms(0, 32, start, start.plusYears(1), "국민", account, holder, null))
				.hasMessageContaining("dueDay");
		assertThatThrownBy(() -> new LeaseTerms(0, 1, start, start, "국민", account, holder, null))
				.hasMessageContaining("endDate");
		assertThatThrownBy(() -> new LeaseTerms(0, 1, start, start.plusDays(1), "가".repeat(51), account, holder, null))
				.hasMessageContaining("bankName");
		assertThat(new LeaseTerms(LeaseTerms.DEPOSIT_MAX, 31, start, start.plusDays(1), "새마을금고", account, holder, " ")
				.memo()).isNull();
		assertThatThrownBy(() -> LeaseRentHistory.requireAmount(LeaseRentHistory.AMOUNT_MAX + 1, "monthlyRent"))
				.isInstanceOf(InvalidLeaseValueException.class);
	}

	@Test
	void wonFormatsAndParses() {
		assertThat(Won.format(500000)).isEqualTo("500,000원");
		assertThat(Won.korean(500000)).isEqualTo("50만 원");
		assertThat(Won.korean(120_000_000)).isEqualTo("1억 2,000만 원");
		assertThat(Won.korean(100_000_000)).isEqualTo("1억 원");
		assertThat(Won.korean(15000)).isEqualTo("1만 5,000원");
		assertThat(Won.korean(0)).isEqualTo("0원");
		assertThat(Won.parse("500,000원")).isEqualTo(500000L);
		assertThat(Won.parse(" 1 000 ")).isEqualTo(1000L);
		assertThat(Won.parse("오십만")).isNull();
		assertThat(Won.parse("-1")).isNull();
		assertThat(Won.parse("")).isNull();
	}

}
