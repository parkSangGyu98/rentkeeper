package com.rentkeeper.notification.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.rentkeeper.notification.domain.NotificationStatus;
import com.rentkeeper.notification.domain.SkipReason;

/**
 * 알림 Outbox에 행을 넣는다 (M5 결정 2, PRD 4.5). 부른 쪽의 트랜잭션 안에서 넣으므로 업무 처리가 롤백되면 알림도 없다.
 * <p>
 * 같은 dedupe_key가 이미 있으면 아무것도 하지 않는다(ON CONFLICT DO NOTHING): 중복이어도 업무 트랜잭션을 깨지
 * 않는다. 세입자 휴대폰 채널이 꺼져 있으면 SKIPPED(CHANNEL_OFF)로 넣는다. 끝난 상태는 다시 바뀌지 않으므로, 나중에
 * 채널을 켜도 그 알림은 나가지 않는다.
 */
@Component
public class NotificationOutbox {

	private static final String INSERT = """
			insert into notification (landlord_id, type, channel, recipient_type, tenant_id, lease_id, charge_id,
			    ops_code, scheduled_for, dedupe_key, status, skip_reason, attempts, next_attempt_at, created_at, updated_at)
			values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?)
			on conflict (dedupe_key) do nothing""";

	private final JdbcTemplate jdbc;

	private final SendWindow sendWindow;

	private final NotificationChannels channels;

	private final Clock clock;

	NotificationOutbox(JdbcTemplate jdbc, SendWindow sendWindow, NotificationChannels channels, Clock clock) {
		this.jdbc = jdbc;
		this.sendWindow = sendWindow;
		this.channels = channels;
		this.clock = clock;
	}

	/**
	 * 보낼 알림을 넣는다.
	 *
	 * @return 새로 넣었으면 true, 같은 알림이 이미 있으면 false
	 */
	@Transactional
	public boolean enqueue(NotificationRequest request) {
		SkipReason reason = channels.enabled(request.type().channel()) ? null : SkipReason.CHANNEL_OFF;
		return insert(request, reason);
	}

	/**
	 * 만들 때부터 보내지 않기로 한 알림을 넣는다(이력에 "보내지 않음"으로 남는다). 같은 키를 차지하므로 그날 다시 만들어지지 않는다.
	 *
	 * @return 새로 넣었으면 true
	 */
	@Transactional
	public boolean enqueueSkipped(NotificationRequest request, SkipReason reason) {
		return insert(request, reason);
	}

	private boolean insert(NotificationRequest request, SkipReason reason) {
		Instant now = clock.instant();
		LocalDate scheduledFor = sendWindow.scheduledFor(request.type(), now);
		Instant firstAttempt = sendWindow.firstAttempt(request.type(), scheduledFor, now);
		NotificationStatus status = reason == null ? NotificationStatus.PENDING : NotificationStatus.SKIPPED;
		Timestamp at = Timestamp.from(now);
		return jdbc.update(INSERT, request.landlordId(), request.type().name(), request.type().channel().name(),
				request.type().recipientType().name(), request.tenantId(), request.leaseId(), request.chargeId(),
				request.opsCode(), scheduledFor, request.dedupeKey(), status.name(),
				reason == null ? null : reason.name(), Timestamp.from(firstAttempt), at, at) == 1;
	}

}
