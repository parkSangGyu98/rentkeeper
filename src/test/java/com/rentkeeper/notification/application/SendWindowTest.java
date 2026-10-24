package com.rentkeeper.notification.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.Test;

import com.rentkeeper.notification.domain.NotificationType;

/** 발송 시간 09:00~20:00 서울의 경계 (M5 결정 3) */
class SendWindowTest {

	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	private static final LocalDate DAY = LocalDate.of(2026, 10, 17);

	private final SendWindow window = new SendWindow(Clock.system(SEOUL),
			new NotificationProperties(null, null, null, null));

	@Test
	void beforeTheEndIsTodayAndWaitsForTheStart() {
		Instant at0859 = at(8, 59);
		assertThat(window.scheduledFor(NotificationType.CHARGE_ISSUED, at0859)).isEqualTo(DAY);
		assertThat(window.firstAttempt(NotificationType.CHARGE_ISSUED, DAY, at0859)).isEqualTo(at(9, 0));

		Instant at0900 = at(9, 0);
		assertThat(window.firstAttempt(NotificationType.CHARGE_ISSUED, DAY, at0900)).isEqualTo(at0900);

		Instant at1959 = at(19, 59);
		assertThat(window.scheduledFor(NotificationType.CHARGE_ISSUED, at1959)).isEqualTo(DAY);
		assertThat(window.firstAttempt(NotificationType.CHARGE_ISSUED, DAY, at1959)).isEqualTo(at1959);
	}

	@Test
	void fromTheEndItIsTomorrowMorning() {
		Instant at2000 = at(20, 0);
		LocalDate tomorrow = window.scheduledFor(NotificationType.CHARGE_ISSUED, at2000);

		assertThat(tomorrow).isEqualTo(DAY.plusDays(1));
		assertThat(window.firstAttempt(NotificationType.CHARGE_ISSUED, tomorrow, at2000))
				.isEqualTo(ZonedDateTime.of(2026, 10, 18, 9, 0, 0, 0, SEOUL).toInstant());
	}

	@Test
	void expiresAtTheEndOfTheScheduledDay() {
		assertThat(window.expired(NotificationType.DUE_REMINDER, DAY, at(19, 59))).isFalse();
		assertThat(window.expired(NotificationType.DUE_REMINDER, DAY, at(20, 0))).isTrue();
		assertThat(window.expired(NotificationType.DUE_REMINDER, DAY.minusDays(1), at(9, 0))).as("어제 것").isTrue();
	}

	@Test
	void opsAlertsIgnoreTheWindow() {
		Instant at2300 = at(23, 0);
		assertThat(window.scheduledFor(NotificationType.OPS_ALERT, at2300)).isEqualTo(DAY);
		assertThat(window.firstAttempt(NotificationType.OPS_ALERT, DAY, at2300)).isEqualTo(at2300);
		assertThat(window.expired(NotificationType.OPS_ALERT, DAY.minusDays(3), at2300)).isFalse();
	}

	private static Instant at(int hour, int minute) {
		return ZonedDateTime.of(2026, 10, 17, hour, minute, 0, 0, SEOUL).toInstant();
	}

}
