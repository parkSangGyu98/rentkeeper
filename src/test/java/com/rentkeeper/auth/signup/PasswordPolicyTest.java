package com.rentkeeper.auth.signup;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.rentkeeper.auth.signup.PasswordPolicy.Violation;
import com.rentkeeper.landlord.domain.EmailAddress;

class PasswordPolicyTest {

	private static final EmailAddress EMAIL = EmailAddress.of("kim.landlord@example.com");

	private final PasswordPolicy policy = new PasswordPolicy();

	@Test
	void loadsAbout100CommonPasswords() {
		assertThat(policy.commonPasswordCount()).isBetween(90, 120);
	}

	@Test
	void requiresAtLeast8Characters() {
		assertThat(policy.check("Rk7#mQz", EMAIL)).contains(Violation.TOO_SHORT);
		assertThat(policy.check("Rk7#mQz2", EMAIL)).isEmpty();
		assertThat(policy.check(null, EMAIL)).contains(Violation.TOO_SHORT);
	}

	@Test
	void countsKoreanCharactersNotBytesForMinimumLength() {
		// 한글 7자는 21바이트지만 7글자라 짧다
		assertThat(policy.check("월세관리비번호", EMAIL)).contains(Violation.TOO_SHORT);
		assertThat(policy.check("월세관리비밀번호", EMAIL)).isEmpty();
	}

	@Test
	void rejectsMoreThan72BytesBecauseBcryptCannotUseThem() {
		String korean24 = "가나다라마바사아자차카타파하거너더러머버서어저처"; // 24자 = 72바이트
		assertThat(korean24.getBytes(StandardCharsets.UTF_8)).hasSize(72);
		assertThat(policy.check(korean24, EMAIL)).isEmpty();
		assertThat(policy.check(korean24 + "커", EMAIL)).contains(Violation.TOO_LONG);
		assertThat(policy.check("a1".repeat(36) + "x", EMAIL)).contains(Violation.TOO_LONG);
	}

	@ParameterizedTest
	@ValueSource(strings = {"12345678", "password1", "PASSWORD1", "Qwer1234", "1q2w3e4r", "iloveyou"})
	void rejectsCommonPasswordsIgnoringCase(String password) {
		assertThat(policy.check(password, EMAIL)).contains(Violation.TOO_COMMON);
	}

	@Test
	void rejectsEmailOrItsLocalPart() {
		assertThat(policy.check("kim.landlord@example.com", EMAIL)).contains(Violation.SAME_AS_EMAIL);
		assertThat(policy.check("KIM.LANDLORD", EMAIL)).contains(Violation.SAME_AS_EMAIL);
	}

	@Test
	void rejectsSingleRepeatedCharacter() {
		assertThat(policy.check("zzzzzzzzzz", EMAIL)).contains(Violation.REPEATED_CHARACTER);
	}

	@Test
	void doesNotRequireCharacterClasses() {
		// NIST 방식: 대문자·특수문자 조합을 강제하지 않는다
		assertThat(policy.check("haengbok villa rent", EMAIL)).isEmpty();
	}

}
