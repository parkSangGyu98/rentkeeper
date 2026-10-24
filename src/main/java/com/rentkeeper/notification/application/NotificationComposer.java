package com.rentkeeper.notification.application;

import java.time.Instant;

import com.rentkeeper.notification.domain.Notification;
import com.rentkeeper.notification.domain.NotificationType;
import com.rentkeeper.notification.domain.SkipReason;

/**
 * 알림 종류별로 보낼 때 받는 곳과 본문을 만든다. 워커의 트랜잭션 안에서 부른다. 받을 월세 상태·수신 거부 같은 조건을 이때 다시 보고,
 * 보내지 않을 이유가 있으면 {@link Composition.Skip}을 돌려준다.
 */
public interface NotificationComposer {

	boolean supports(NotificationType type);

	Composition compose(Notification notification, Instant now);

	/** 만든 결과 */
	sealed interface Composition {

		/** 보낸다. 값은 저장하지 않고 발송기에만 넘긴다 */
		record Send(String to, String subject, String body) implements Composition {

			@Override
			public String toString() {
				return "Send[***]";
			}

		}

		/** 보내지 않는다 */
		record Skip(SkipReason reason) implements Composition {
		}

	}

}
