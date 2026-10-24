package com.rentkeeper.notification.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;

import org.springframework.stereotype.Component;

import com.rentkeeper.notification.domain.NotificationType;

/**
 * 발송 시간 09:00~20:00 서울 (M5 결정 3). 20:00 뒤에 만든 알림은 다음 날 09:00에 보내고, 보내기로 한 날
 * 20:00까지 못 보내면 버린다(EXPIRED). 날짜가 들어간 문구("내일이 보내실 날…")가 하루 늦게 나가지 않게 하려는 것이다.
 * 운영 알림만 제한이 없다.
 */
@Component
public class SendWindow {

	private final Clock clock;

	private final int startHour;

	private final int endHour;

	public SendWindow(Clock clock, NotificationProperties properties) {
		this.clock = clock;
		this.startHour = properties.sendWindowStartHour();
		this.endHour = properties.sendWindowEndHour();
	}

	/** 지금 만든 알림을 보낼 날 (서울): 끝 시각 전이면 오늘, 뒤면 내일. 운영 알림은 오늘 */
	public LocalDate scheduledFor(NotificationType type, Instant now) {
		ZonedDateTime local = now.atZone(clock.getZone());
		if (!type.windowed() || local.getHour() < endHour) {
			return local.toLocalDate();
		}
		return local.toLocalDate().plusDays(1);
	}

	/** 처음 보낼 수 있는 시각: 보낼 날의 시작 시각 이후. 운영 알림은 지금 */
	public Instant firstAttempt(NotificationType type, LocalDate scheduledFor, Instant now) {
		if (!type.windowed()) {
			return now;
		}
		Instant start = scheduledFor.atTime(startHour, 0).atZone(clock.getZone()).toInstant();
		return now.isAfter(start) ? now : start;
	}

	/** 보낼 날의 발송 시간이 끝났는지 (그 뒤로는 보내지 않고 버린다) */
	public boolean expired(NotificationType type, LocalDate scheduledFor, Instant now) {
		if (!type.windowed()) {
			return false;
		}
		Instant end = endHour == 24
				? scheduledFor.plusDays(1).atStartOfDay(clock.getZone()).toInstant()
				: scheduledFor.atTime(endHour, 0).atZone(clock.getZone()).toInstant();
		return !now.isBefore(end);
	}

}
