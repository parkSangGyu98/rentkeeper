package com.rentkeeper.support;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.Ordered;

import com.rentkeeper.notification.application.NotificationChannels;
import com.rentkeeper.notification.application.NotificationComposer;
import com.rentkeeper.notification.application.NotificationProperties;
import com.rentkeeper.notification.application.NotificationSender;
import com.rentkeeper.notification.application.OutboundNotification;
import com.rentkeeper.notification.domain.Notification;
import com.rentkeeper.notification.domain.NotificationChannel;
import com.rentkeeper.notification.domain.NotificationType;

/**
 * 알림 테스트용 부품. 보낸 메시지를 모으는 발송기, 켜고 끌 수 있는 세입자 휴대폰 채널, 필요할 때만 쓰는 시험용 본문 만들기. 이
 * 설정을 쓰는 테스트는 같은 컨텍스트를 함께 쓰므로 {@link #reset}으로 처음 상태로 돌린다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class NotificationTestConfiguration {

	@Bean
	RecordingNotificationSender recordingNotificationSender() {
		return new RecordingNotificationSender();
	}

	@Bean
	@Primary
	MutableNotificationChannels mutableNotificationChannels(NotificationProperties properties) {
		return new MutableNotificationChannels(properties);
	}

	@Bean
	TestComposer testComposer() {
		return new TestComposer();
	}

	public static void reset(RecordingNotificationSender sender, MutableNotificationChannels channels,
			TestComposer composer) {
		sender.reset();
		channels.tenantPhone(false);
		composer.handle(Set.of());
	}

	/** 보낸 메시지를 모은다. failNext로 다음 몇 번을 실패시킨다 */
	public static class RecordingNotificationSender implements NotificationSender {

		private final List<OutboundNotification> sent = new CopyOnWriteArrayList<>();

		private final AtomicInteger failures = new AtomicInteger();

		private volatile long delayMillis;

		@Override
		public boolean supports(NotificationChannel channel) {
			return true;
		}

		@Override
		public void send(OutboundNotification message) {
			if (delayMillis > 0) {
				try {
					Thread.sleep(delayMillis);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
			if (failures.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
				throw new IllegalStateException("시험용 실패 01099990000 비밀본문");
			}
			sent.add(message);
		}

		public List<OutboundNotification> sent() {
			return List.copyOf(sent);
		}

		public void failNext(int times) {
			failures.set(times);
		}

		public void delay(long millis) {
			delayMillis = millis;
		}

		void reset() {
			sent.clear();
			failures.set(0);
			delayMillis = 0;
		}

	}

	/** 세입자 휴대폰 채널을 테스트 안에서 켜고 끈다 (실제로는 설정값) */
	public static class MutableNotificationChannels extends NotificationChannels {

		private volatile boolean tenantPhone;

		MutableNotificationChannels(NotificationProperties properties) {
			super(properties);
		}

		public void tenantPhone(boolean enabled) {
			tenantPhone = enabled;
		}

		@Override
		public boolean enabled(NotificationChannel channel) {
			return channel == NotificationChannel.EMAIL || tenantPhone;
		}

	}

	/** 고른 종류만 시험용 본문으로 보낸다. 고르지 않은 종류는 실제 구현이 맡는다 */
	public static class TestComposer implements NotificationComposer, Ordered {

		public static final String TO = "01011112222";

		public static final String BODY = "시험 본문 비밀링크 http://localhost:8080/t/secret";

		private volatile Set<NotificationType> types = Set.of();

		public void handle(Set<NotificationType> handled) {
			types = handled.isEmpty() ? Set.of() : EnumSet.copyOf(handled);
		}

		@Override
		public boolean supports(NotificationType type) {
			return types.contains(type);
		}

		@Override
		public Composition compose(Notification notification, Instant now) {
			return new Composition.Send(TO, "시험 제목", BODY);
		}

		@Override
		public int getOrder() {
			return Ordered.HIGHEST_PRECEDENCE;
		}

	}

}
