package com.rentkeeper.notification.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.rentkeeper.common.ops.OpsAlertNotifier;
import com.rentkeeper.notification.application.NotificationComposer.Composition;
import com.rentkeeper.notification.domain.Notification;
import com.rentkeeper.notification.domain.NotificationRepository;
import com.rentkeeper.notification.domain.NotificationStatus;
import com.rentkeeper.notification.domain.SkipReason;

/**
 * 알림 발송 워커 (M5 결정 2·3, ADR-028). {@link #runOnce()}를 30초마다
 * 부른다(NotificationSendJob).
 * <ol>
 * <li>가져가기: 보낼 차례가 된 행(기다림, 또는 잠금이 만료된 보내는 중)을 {@code FOR UPDATE SKIP LOCKED}로
 * 최대 {@value #BATCH}건 잠그고 SENDING으로 바꾼다. 워커가 둘이어도 같은 행을 가져가지 않는다.</li>
 * <li>준비(트랜잭션): 발송 시간이 지났으면 EXPIRED, 채널이 꺼졌으면 SKIPPED. 종류별
 * {@link NotificationComposer}가 상태를 다시 보고 받는 곳·본문을 만든다(저장하지 않음).</li>
 * <li>보내기(트랜잭션 밖): 발송기에 넘긴다.</li>
 * <li>기록(트랜잭션): SENT, 또는 실패하면 1분·5분·15분 뒤 다시, 다 쓰면 FAILED와 운영 알림.</li>
 * </ol>
 * 보낸 뒤 SENT를 기록하기 전에 서버가 죽으면 잠금이 만료된 뒤 다시 보낸다(최소 1회 발송, 위험 1). 로그에는 ID·종류·결과만
 * 남긴다.
 */
@Component
public class NotificationWorker {

	public static final String LOGGER_NAME = "rentkeeper.notification";

	public static final String ALERT_CODE = "NOTIFICATION_ALERT";

	static final int BATCH = 50;

	static final Duration CLAIM = Duration.ofMinutes(5);

	private static final Logger log = LoggerFactory.getLogger(LOGGER_NAME);

	private static final String CLAIMABLE = """
			select id from notification
			where (status = 'PENDING' and next_attempt_at <= ?) or (status = 'SENDING' and claimed_until <= ?)
			order by next_attempt_at, id
			limit ?
			for update skip locked""";

	private final JdbcTemplate jdbc;

	private final NotificationRepository notifications;

	private final List<NotificationComposer> composers;

	private final NotificationSenders senders;

	private final NotificationChannels channels;

	private final SendWindow sendWindow;

	private final OpsAlertNotifier opsAlerts;

	private final Clock clock;

	private final TransactionTemplate transaction;

	NotificationWorker(JdbcTemplate jdbc, NotificationRepository notifications, List<NotificationComposer> composers,
			NotificationSenders senders, NotificationChannels channels, SendWindow sendWindow,
			OpsAlertNotifier opsAlerts, Clock clock, PlatformTransactionManager transactionManager) {
		this.jdbc = jdbc;
		this.notifications = notifications;
		this.composers = composers;
		this.senders = senders;
		this.channels = channels;
		this.sendWindow = sendWindow;
		this.opsAlerts = opsAlerts;
		this.clock = clock;
		this.transaction = new TransactionTemplate(transactionManager);
	}

	/** 한 번 가져가 보낸 결과 (건수만) */
	public record Result(int claimed, int sent, int skipped, int expired, int retried, int failed) {
	}

	public Result runOnce() {
		List<Long> ids = transaction.execute(status -> claim());
		int sent = 0;
		int skipped = 0;
		int expired = 0;
		int retried = 0;
		int failed = 0;
		for (long id : ids) {
			switch (process(id)) {
				case SENT -> sent++;
				case SKIPPED -> skipped++;
				case EXPIRED -> expired++;
				case PENDING -> retried++;
				case FAILED -> failed++;
				default -> {
				}
			}
		}
		Result result = new Result(ids.size(), sent, skipped, expired, retried, failed);
		if (!ids.isEmpty()) {
			log.info("notification batch done: {}", result);
		}
		return result;
	}

	private List<Long> claim() {
		Timestamp now = Timestamp.from(clock.instant());
		List<Long> ids = jdbc.queryForList(CLAIMABLE, Long.class, now, now, BATCH);
		if (ids.isEmpty()) {
			return ids;
		}
		Instant until = clock.instant().plus(CLAIM);
		List<Notification> claimed = notifications.findForWorkerByIdIn(ids);
		claimed.forEach(n -> n.claim(until));
		notifications.flush();
		return claimed.stream().map(Notification::getId).sorted().toList();
	}

	/** 행 하나를 준비 → 보내기 → 기록. 결과 상태를 돌려준다(다른 워커가 다시 가져갔으면 SENDING) */
	private NotificationStatus process(long id) {
		Prepared prepared;
		try {
			prepared = transaction.execute(status -> prepare(id));
		} catch (RuntimeException e) {
			return recordFailure(id, null, e);
		}
		if (prepared.message() == null) {
			return prepared.outcome();
		}
		try {
			prepared.sender().send(prepared.message());
		} catch (RuntimeException e) {
			return recordFailure(id, prepared.version(), e);
		}
		return transaction.execute(status -> {
			Optional<Notification> found = current(id, prepared.version());
			if (found.isEmpty()) {
				log.warn("notification id={} result=RECLAIMED (sent, but another worker took it over)", id);
				return NotificationStatus.SENDING;
			}
			Notification n = found.get();
			n.markSent(clock.instant());
			notifications.flush();
			log(n, "SENT");
			return NotificationStatus.SENT;
		});
	}

	private record Prepared(NotificationStatus outcome, OutboundNotification message, NotificationSender sender,
			long version) {
	}

	private Prepared prepare(long id) {
		Optional<Notification> found = notifications.findForWorkerById(id)
				.filter(n -> n.getStatus() == NotificationStatus.SENDING);
		if (found.isEmpty()) {
			return new Prepared(NotificationStatus.SENDING, null, null, 0);
		}
		Notification n = found.get();
		Instant now = clock.instant();
		if (sendWindow.expired(n.getType(), n.getScheduledFor(), now)) {
			n.expire();
			return finish(n, NotificationStatus.EXPIRED, "EXPIRED");
		}
		if (!channels.enabled(n.getChannel())) {
			return skip(n, SkipReason.CHANNEL_OFF);
		}
		NotificationComposer composer = composers.stream().filter(c -> c.supports(n.getType())).findFirst()
				.orElseThrow(() -> new IllegalStateException("no composer for " + n.getType()));
		NotificationSender sender = senders.forChannel(n.getChannel())
				.orElseThrow(() -> new IllegalStateException("no sender for " + n.getChannel()));
		Composition composition = composer.compose(n, now);
		if (composition instanceof Composition.Skip skip) {
			return skip(n, skip.reason());
		}
		Composition.Send send = (Composition.Send) composition;
		notifications.flush();
		OutboundNotification message = new OutboundNotification(n.getId(), n.getType(), n.getChannel(),
				n.recipientLabel(), send.to(), send.subject(), send.body());
		return new Prepared(NotificationStatus.SENDING, message, sender, n.getVersion());
	}

	private Prepared skip(Notification n, SkipReason reason) {
		n.skip(reason);
		return finish(n, NotificationStatus.SKIPPED, "SKIPPED:" + reason);
	}

	private Prepared finish(Notification n, NotificationStatus outcome, String result) {
		notifications.flush();
		log(n, result);
		return new Prepared(outcome, null, null, n.getVersion());
	}

	/** 실패 기록. version이 있으면 그 사이 다른 워커가 가져가지 않았을 때만 */
	private NotificationStatus recordFailure(long id, Long version, RuntimeException error) {
		String errorClass = error.getClass().getName();
		NotificationStatus outcome = transaction.execute(status -> {
			Optional<Notification> found = version == null
					? notifications.findForWorkerById(id).filter(n -> n.getStatus() == NotificationStatus.SENDING)
					: current(id, version);
			if (found.isEmpty()) {
				return NotificationStatus.SENDING;
			}
			Notification n = found.get();
			boolean failed = n.markAttemptFailed(clock.instant(), errorClass);
			notifications.flush();
			log(n, (failed ? "FAILED" : "RETRY") + " error=" + error.getClass().getSimpleName());
			return n.getStatus();
		});
		if (outcome == NotificationStatus.FAILED) {
			opsAlerts.alert(ALERT_CODE, "failed=1 id=%d".formatted(id));
		}
		return outcome;
	}

	/** 준비한 뒤 그대로인 행 (다른 워커가 잠금 만료 뒤 다시 가져갔으면 version이 다르다) */
	private Optional<Notification> current(long id, long version) {
		return notifications.findForWorkerById(id)
				.filter(n -> n.getStatus() == NotificationStatus.SENDING && n.getVersion() == version);
	}

	private static void log(Notification n, String result) {
		log.info("notification id={} type={} channel={} recipient={} result={}", n.getId(), n.getType(), n.getChannel(),
				n.recipientLabel(), result);
	}

}
