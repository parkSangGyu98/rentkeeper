package com.rentkeeper.notification.application;

import com.rentkeeper.notification.domain.NotificationChannel;

/**
 * 알림 발송기 (M5 결정 11). M5에는 로그 기록용({@link LoggingNotificationSender}, local 전용)만
 * 있다. 실제 메일·문자 발송기는 M6 이후에 이 인터페이스의 구현으로 붙인다.
 * <p>
 * 실패하면 예외를 던진다. 예외 메시지에 받는 곳·본문을 넣지 않는다(워커는 예외 클래스 이름만 기록한다).
 */
public interface NotificationSender {

	boolean supports(NotificationChannel channel);

	void send(OutboundNotification message);

}
