package com.rentkeeper.landlord.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;

/**
 * 탈퇴 상태 전이 (ADR-015): ACTIVE → WITHDRAWN 한 방향만, 연결 끊기 기록은 탈퇴한 카카오 계정만.
 */
class LandlordWithdrawalTest {

	private static final Instant NOW = Instant.parse("2026-10-17T00:00:00Z");

	@Test
	void activeAccountWithdraws() {
		Landlord landlord = local();

		landlord.withdraw(NOW);

		assertThat(landlord.getStatus()).isEqualTo(LandlordStatus.WITHDRAWN);
		assertThat(landlord.getWithdrawnAt()).isEqualTo(NOW);
	}

	@Test
	void withdrawnAccountCannotWithdrawAgain() {
		Landlord landlord = local();
		landlord.withdraw(NOW);

		assertThatThrownBy(() -> landlord.withdraw(NOW.plusSeconds(60))).isInstanceOf(IllegalStateException.class);
		assertThat(landlord.getWithdrawnAt()).isEqualTo(NOW);
	}

	@Test
	void kakaoUnlinkIsRecordedOnlyAfterWithdrawal() {
		Landlord landlord = kakao();
		assertThatThrownBy(() -> landlord.markKakaoUnlinked(NOW)).isInstanceOf(IllegalStateException.class);

		landlord.withdraw(NOW);
		landlord.markKakaoUnlinked(NOW.plusSeconds(1));
		landlord.markKakaoUnlinked(NOW.plusSeconds(99));

		assertThat(landlord.getKakaoUnlinkedAt()).as("처음 성공한 시각을 유지한다").isEqualTo(NOW.plusSeconds(1));
	}

	@Test
	void localAccountHasNoKakaoUnlink() {
		Landlord landlord = local();
		landlord.withdraw(NOW);

		assertThatThrownBy(() -> landlord.markKakaoUnlinked(NOW)).isInstanceOf(IllegalStateException.class);
		assertThat(landlord.getKakaoUnlinkedAt()).isNull();
	}

	private static Landlord local() {
		return Landlord.registerLocal(EmailAddress.of("kim@example.com"), "{bcrypt}hash", "김임대");
	}

	private static Landlord kakao() {
		return Landlord.registerKakao("123", "김카카오", null, null);
	}

}
