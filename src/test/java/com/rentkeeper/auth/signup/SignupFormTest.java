package com.rentkeeper.auth.signup;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SignupFormTest {

	@Test
	void toStringHidesPasswords() {
		SignupForm form = new SignupForm("kim@example.com", "Secret-Pass-1", "Secret-Pass-2", "김임대", true);

		assertThat(form.toString()).contains("kim@example.com").doesNotContain("Secret-Pass-1")
				.doesNotContain("Secret-Pass-2");
	}

}
