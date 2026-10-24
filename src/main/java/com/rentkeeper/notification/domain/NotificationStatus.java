package com.rentkeeper.notification.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 알림 발송 상태와 전이표 (M5 결정 2). SENT·FAILED·SKIPPED·EXPIRED는 끝난 상태라 다시 바뀌지 않는다. 그래서
 * 채널을 나중에 켜도 이미 SKIPPED인 알림은 다시 나가지 않는다.
 */
public enum NotificationStatus {

	/** 기다림 */
	PENDING,

	/** 워커가 가져가 보내는 중 (claimed_until까지) */
	SENDING,

	/** 보냄 */
	SENT,

	/** 여러 번 실패해 그만둠 */
	FAILED,

	/** 보내지 않기로 함 (skip_reason) */
	SKIPPED,

	/** 보낼 날이 지나 버림 */
	EXPIRED;

	private static final Map<NotificationStatus, Set<NotificationStatus>> TRANSITIONS = Map.of(PENDING,
			EnumSet.of(SENDING, SKIPPED, EXPIRED), SENDING,
			EnumSet.of(SENDING, PENDING, SENT, FAILED, SKIPPED, EXPIRED), SENT,
			EnumSet.noneOf(NotificationStatus.class), FAILED, EnumSet.noneOf(NotificationStatus.class), SKIPPED,
			EnumSet.noneOf(NotificationStatus.class), EXPIRED, EnumSet.noneOf(NotificationStatus.class));

	public boolean canMoveTo(NotificationStatus next) {
		return TRANSITIONS.get(this).contains(next);
	}

	public boolean finished() {
		return TRANSITIONS.get(this).isEmpty();
	}

}
