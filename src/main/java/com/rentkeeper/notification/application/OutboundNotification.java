package com.rentkeeper.notification.application;

import com.rentkeeper.notification.domain.NotificationChannel;
import com.rentkeeper.notification.domain.NotificationType;

/**
 * 발송기에 넘기는 메시지 한 건. 보낼 때 만들고 저장하지 않는다. 받는 곳(번호·주소)과 본문(링크 원문 포함)이 들어 있으므로
 * {@link #toString()}은 ID와 종류만 낸다. 로그·예외에 이 값을 그대로 넣지 않는다.
 *
 * @param notificationId
 *            알림 ID (대행사에 넘길 멱등 키)
 * @param recipient
 *            받는 사람을 ID로만 적은 것 (예: TENANT:12, 로그용)
 * @param to
 *            받는 곳 (휴대폰 번호 또는 메일 주소)
 */
public record OutboundNotification(long notificationId, NotificationType type, NotificationChannel channel,
		String recipient, String to, String subject, String body) {

	@Override
	public String toString() {
		return "OutboundNotification[id=" + notificationId + ", type=" + type + ", channel=" + channel + ", recipient="
				+ recipient + ", to=***, body=***]";
	}

}
