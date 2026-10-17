package com.rentkeeper.auth.kakao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class KakaoProfileAndPropertiesTest {

	@Test
	void emailIsTrustedOnlyWhenValidAndVerified() {
		assertThat(profile("Kim@Example.com", true, true).email()).isEqualTo("kim@example.com");
		assertThat(profile("kim@example.com", true, true).emailTrusted()).isTrue();

		assertThat(profile("kim@example.com", true, false).email()).as("유효하지만 미인증 → 우리 인증 메일")
				.isEqualTo("kim@example.com");
		assertThat(profile("kim@example.com", true, false).emailTrusted()).isFalse();

		assertThat(profile("kim@example.com", false, true).email()).as("is_email_valid=false → 쓰지 않음").isNull();
		assertThat(profile("한글@example.com", true, true).email()).as("우리 형식에 맞지 않으면 직접 입력").isNull();
	}

	@Test
	void emailIsIgnoredWhenEmailScopeIsNotRequested() {
		// 지금 기본 경로: account_email을 요청하지 않으므로 응답에 인증된 이메일이 있어도 쓰지 않는다 (ADR-014)
		KakaoProfile profile = KakaoProfile.from(Map.of("id", 1L, "kakao_account",
				Map.of("email", "kim@example.com", "is_email_valid", true, "is_email_verified", true)), false);

		assertThat(profile.email()).isNull();
		assertThat(profile.emailTrusted()).isFalse();
	}

	@Test
	void missingEmailAndNicknameAreNull() {
		KakaoProfile profile = KakaoProfile.from(Map.of("id", 123L, "kakao_account", Map.of()), true);

		assertThat(profile.kakaoId()).isEqualTo("123");
		assertThat(profile.email()).isNull();
		assertThat(profile.nickname()).isNull();
	}

	@Test
	void nicknameIsTrimmedToFiftyCharacters() {
		KakaoProfile profile = KakaoProfile.from(
				Map.of("id", 1L, "kakao_account", Map.of("profile", Map.of("nickname", "  " + "가".repeat(60) + "  "))),
				false);

		assertThat(profile.nickname()).hasSize(50);
	}

	@Test
	void toStringHidesPersonalInfo() {
		assertThat(profile("kim@example.com", true, true).toString()).doesNotContain("kim@example.com");
	}

	@Test
	void propertiesMustBeAllOrNothing() {
		new KakaoProperties("", "", "", "a", "t", "u", "x", List.of("profile_nickname")).validate(false);
		assertThatThrownBy(
				() -> new KakaoProperties("", "", "", "a", "t", "u", "x", List.of("profile_nickname")).validate(true))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("운영(prod)");
		assertThatThrownBy(
				() -> new KakaoProperties("id", "secret", "", "a", "t", "u", "x", List.of("profile_nickname"))
						.validate(false))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("모두 있거나 모두 없어야");
		new KakaoProperties("id", "secret", "admin", "a", "t", "u", "x", List.of("profile_nickname")).validate(true);
	}

	@Test
	void propertiesToStringHidesKeys() {
		assertThat(
				new KakaoProperties("my-id", "my-secret", "my-admin", "a", "t", "u", "x", List.of("profile_nickname"))
						.toString())
				.doesNotContain("my-id").doesNotContain("my-secret").doesNotContain("my-admin");
	}

	private static KakaoProfile profile(String email, boolean valid, boolean verified) {
		return KakaoProfile.from(Map.of("id", 1L, "kakao_account",
				Map.of("email", email, "is_email_valid", valid, "is_email_verified", verified)), true);
	}

}
