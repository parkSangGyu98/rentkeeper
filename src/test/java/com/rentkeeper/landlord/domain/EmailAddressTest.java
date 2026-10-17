package com.rentkeeper.landlord.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.rentkeeper.landlord.domain.EmailAddress.InvalidEmailException;

class EmailAddressTest {

	@Test
	void normalizesWhitespaceAndCase() {
		assertThat(EmailAddress.of("  Kim.Landlord@Example.COM \t").value()).isEqualTo("kim.landlord@example.com");
	}

	@Test
	void sameAddressWithDifferentCaseIsEqual() {
		assertThat(EmailAddress.of("User@Example.com")).isEqualTo(EmailAddress.of("user@example.com"));
	}

	@Test
	void lowercasesIndependentlyOfDefaultLocale() {
		Locale original = Locale.getDefault();
		try {
			// 터키어 로케일의 기본 toLowerCase()는 'I'를 점 없는 'ı'로 바꾼다
			Locale.setDefault(Locale.forLanguageTag("tr-TR"));
			assertThat(EmailAddress.of("ADMIN@EXAMPLE.COM").value()).isEqualTo("admin@example.com");
		} finally {
			Locale.setDefault(original);
		}
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {"plainaddress", "@example.com", "user@", "user@localhost", "user@@example.com",
			"user name@example.com", "user@exa mple.com", "user@-example.com", "홍길동@example.com", "user@예시.com"})
	void rejectsInvalidFormat(String raw) {
		assertThatThrownBy(() -> EmailAddress.of(raw)).isInstanceOf(InvalidEmailException.class);
	}

	@Test
	void rejectsLongerThan254Characters() {
		String domain = "@example.com";
		String ok = "a".repeat(EmailAddress.MAX_LENGTH - domain.length()) + domain;

		assertThat(EmailAddress.of(ok).value()).hasSize(EmailAddress.MAX_LENGTH);
		assertThatThrownBy(() -> EmailAddress.of("a" + ok)).isInstanceOf(InvalidEmailException.class);
	}

	@Test
	void constructorRejectsNonNormalizedValue() {
		// 정규화를 건너뛴 값은 타입으로 만들어질 수 없다
		assertThatThrownBy(() -> new EmailAddress("User@Example.com")).isInstanceOf(InvalidEmailException.class);
	}

}
