package com.rentkeeper.notification.application;

import org.springframework.stereotype.Component;

import com.rentkeeper.notification.domain.NotificationChannel;

/**
 * 채널을 쓸지 (M5 결정 6). 메일은 늘 켜져 있다(임대인 메일은 인증 여부를 따로 본다). 세입자 휴대폰은 서비스 설정을 따르고 기본은
 * 꺼짐이다: 실제 문자 발송기가 없는 환경에서는 세입자 알림을 보내지 않는다.
 */
@Component
public class NotificationChannels {

	private final NotificationProperties properties;

	public NotificationChannels(NotificationProperties properties) {
		this.properties = properties;
	}

	public boolean enabled(NotificationChannel channel) {
		return switch (channel) {
			case EMAIL -> true;
			case TENANT_PHONE -> properties.tenantPhone().enabled();
		};
	}

}
