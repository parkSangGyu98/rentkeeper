package com.rentkeeper.landlord.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.rentkeeper.landlord.domain.Landlord.InvalidNameException;

class LandlordTest {

	private static final EmailAddress EMAIL = EmailAddress.of("kim@example.com");

	@Test
	void registerLocalStartsActiveOnFreePlan() {
		Landlord landlord = Landlord.registerLocal(EMAIL, "{bcrypt}hash", "  김임대  ");

		assertThat(landlord.getAuthProvider()).isEqualTo(AuthProvider.LOCAL);
		assertThat(landlord.getStatus()).isEqualTo(LandlordStatus.ACTIVE);
		assertThat(landlord.getPlan()).isEqualTo(Plan.FREE);
		assertThat(landlord.getName()).isEqualTo("김임대");
		assertThat(landlord.getEmail()).isEqualTo("kim@example.com");
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {"   "})
	void rejectsBlankName(String name) {
		assertThatThrownBy(() -> Landlord.registerLocal(EMAIL, "{bcrypt}hash", name))
				.isInstanceOf(InvalidNameException.class);
	}

	@Test
	void rejectsNameLongerThan50Characters() {
		assertThat(Landlord.registerLocal(EMAIL, "{bcrypt}hash", "가".repeat(50)).getName()).hasSize(50);
		assertThatThrownBy(() -> Landlord.registerLocal(EMAIL, "{bcrypt}hash", "가".repeat(51)))
				.isInstanceOf(InvalidNameException.class);
	}

	@Test
	void requiresPasswordHash() {
		assertThatThrownBy(() -> Landlord.registerLocal(EMAIL, " ", "김임대"))
				.isInstanceOf(IllegalArgumentException.class);
	}

}
