package com.rentkeeper.notification.domain;

/** 전이표에 없는 상태 변경 (코드 버그) */
public class NotificationStateException extends IllegalStateException {

	private static final long serialVersionUID = 1L;

	public NotificationStateException(NotificationStatus from, NotificationStatus to) {
		super("notification status " + from + " -> " + to + " is not allowed");
	}

}
