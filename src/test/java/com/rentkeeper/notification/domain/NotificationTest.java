package com.rentkeeper.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** 알림 상태 전이표와 재시도 (M5 결정 2) */
class NotificationTest {

	private static final Instant NOW = Instant.parse("2026-10-17T00:00:00Z");

	@Test
	void transitionTable() {
		assertThat(allowed(NotificationStatus.PENDING)).containsExactlyInAnyOrder(NotificationStatus.SENDING,
				NotificationStatus.SKIPPED, NotificationStatus.EXPIRED);
		assertThat(allowed(NotificationStatus.SENDING)).containsExactlyInAnyOrder(NotificationStatus.SENDING,
				NotificationStatus.PENDING, NotificationStatus.SENT, NotificationStatus.FAILED,
				NotificationStatus.SKIPPED, NotificationStatus.EXPIRED);
		for (NotificationStatus finished : EnumSet.of(NotificationStatus.SENT, NotificationStatus.FAILED,
				NotificationStatus.SKIPPED, NotificationStatus.EXPIRED)) {
			assertThat(allowed(finished)).as("%s는 끝난 상태라 다시 바뀌지 않는다", finished).isEmpty();
			assertThat(finished.finished()).isTrue();
		}
	}

	@Test
	void claimThenSent() {
		Notification n = pending();

		n.claim(NOW.plus(Duration.ofMinutes(5)));
		assertThat(n.getStatus()).isEqualTo(NotificationStatus.SENDING);
		assertThat(n.getAttempts()).isEqualTo(1);
		n.markSent(NOW);

		assertThat(n.getStatus()).isEqualTo(NotificationStatus.SENT);
		assertThat(n.getSentAt()).isEqualTo(NOW);
		assertThat(n.getClaimedUntil()).isNull();
	}

	@Test
	void retriesAfterOneFiveFifteenMinutesThenFails() {
		Notification n = pending();
		Instant at = NOW;
		for (Duration delay : Notification.RETRY_DELAYS.subList(0, Notification.MAX_ATTEMPTS - 1)) {
			n.claim(at.plusSeconds(300));
			assertThat(n.markAttemptFailed(at, "java.io.IOException")).isFalse();
			assertThat(n.getStatus()).isEqualTo(NotificationStatus.PENDING);
			assertThat(n.getNextAttemptAt()).isEqualTo(at.plus(delay));
			at = n.getNextAttemptAt();
		}
		n.claim(at.plusSeconds(300));

		assertThat(n.markAttemptFailed(at, "java.io.IOException")).isTrue();
		assertThat(n.getStatus()).isEqualTo(NotificationStatus.FAILED);
		assertThat(n.getAttempts()).isEqualTo(3);
		assertThat(n.getLastError()).isEqualTo("java.io.IOException");
	}

	@Test
	void skippedAndExpiredCannotBeSentLater() {
		Notification skipped = pending();
		skipped.skip(SkipReason.CHANNEL_OFF);
		assertThat(skipped.getSkipReason()).isEqualTo(SkipReason.CHANNEL_OFF);
		assertThatThrownBy(() -> skipped.claim(NOW)).isInstanceOf(NotificationStateException.class);

		Notification expired = pending();
		expired.expire();
		assertThatThrownBy(() -> expired.claim(NOW)).isInstanceOf(NotificationStateException.class);
	}

	@Test
	void pendingCannotBeMarkedSentWithoutClaim() {
		assertThatThrownBy(() -> pending().markSent(NOW)).isInstanceOf(NotificationStateException.class);
	}

	private static Set<NotificationStatus> allowed(NotificationStatus from) {
		Set<NotificationStatus> allowed = EnumSet.noneOf(NotificationStatus.class);
		for (NotificationStatus to : NotificationStatus.values()) {
			if (from.canMoveTo(to)) {
				allowed.add(to);
			}
		}
		return allowed;
	}

	private static Notification pending() {
		return Notification.pendingForTest(NotificationType.OVERDUE_DIGEST, NOW, LocalDate.of(2026, 10, 17));
	}

}
