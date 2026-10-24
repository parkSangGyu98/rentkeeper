package com.rentkeeper.notification.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.rentkeeper.lease.application.LeaseService;
import com.rentkeeper.lease.application.LeaseViews.LeaseSummary;
import com.rentkeeper.notification.domain.Notification;
import com.rentkeeper.notification.domain.NotificationRepository;
import com.rentkeeper.notification.domain.NotificationStatus;
import com.rentkeeper.notification.domain.NotificationType;
import com.rentkeeper.notification.domain.SkipReason;

/**
 * 알림 이력 (NOTI-7, M5 결정 15). 로그인한 임대인의 최근 {@value #DAYS}일 알림을
 * {@value #PAGE_SIZE}건씩. 본문은 저장하지 않으므로 보여 줄 수 없다: 종류·받는 사람·상태·시각만.
 */
@Service
@Transactional(readOnly = true)
public class NotificationHistory {

	static final int DAYS = 90;

	static final int PAGE_SIZE = 50;

	private final NotificationRepository notifications;

	private final LeaseService leaseService;

	private final Clock clock;

	NotificationHistory(NotificationRepository notifications, LeaseService leaseService, Clock clock) {
		this.notifications = notifications;
		this.leaseService = leaseService;
		this.clock = clock;
	}

	/**
	 * 이력 한 줄
	 *
	 * @param at
	 *            보낸 시각(보냈으면) 또는 만든 시각 (서울)
	 */
	public record Row(String typeText, String recipientText, String statusText, boolean sent, LocalDateTime at) {
	}

	/**
	 * @param hasNext
	 *            다음 쪽이 있는지
	 */
	public record Page(List<Row> rows, int page, boolean hasNext) {
	}

	public Page page(long landlordId, int page) {
		int safePage = Math.max(0, page);
		Instant after = clock.instant().minus(Duration.ofDays(DAYS));
		Slice<Notification> slice = notifications.findByLandlordIdAndCreatedAtAfterOrderByCreatedAtDescIdDesc(
				landlordId, after, PageRequest.of(safePage, PAGE_SIZE));
		Map<Long, LeaseSummary> leases = leaseService.summaries(landlordId,
				slice.getContent().stream().map(Notification::getLeaseId).filter(Objects::nonNull).distinct().toList());
		List<Row> rows = slice.getContent().stream().map(n -> row(n, leases)).toList();
		return new Page(rows, safePage, slice.hasNext());
	}

	private Row row(Notification n, Map<Long, LeaseSummary> leases) {
		Instant at = n.getSentAt() != null ? n.getSentAt() : n.getCreatedAt();
		return new Row(typeText(n.getType()), recipientText(n, leases), statusText(n),
				n.getStatus() == NotificationStatus.SENT, LocalDateTime.ofInstant(at, clock.getZone()));
	}

	private static String recipientText(Notification n, Map<Long, LeaseSummary> leases) {
		return switch (n.getRecipientType()) {
			case TENANT -> {
				LeaseSummary lease = leases.get(n.getLeaseId());
				yield (lease == null ? "" : lease.unitName() + " ") + "세입자 (문자)";
			}
			case LANDLORD -> "나 (메일)";
			case OPS -> "운영자";
		};
	}

	static String typeText(NotificationType type) {
		return switch (type) {
			case CHARGE_ISSUED -> "받을 월세 안내";
			case DUE_REMINDER -> "납부일 전날 안내";
			case OVERDUE_NOTICE -> "납부일 지남 안내";
			case TENANT_REPORT_DIGEST -> "입금 신고 알림";
			case OVERDUE_DIGEST -> "납부일 지남 요약";
			case LEASE_END_DIGEST -> "곧 끝나는 계약";
			case OPS_ALERT -> "운영 알림";
		};
	}

	static String statusText(Notification n) {
		return switch (n.getStatus()) {
			case PENDING, SENDING -> "보낼 차례예요";
			case SENT -> "보냈어요";
			case FAILED -> "보내지 못했어요";
			case EXPIRED -> "보내지 않음: 보낼 시간이 지났어요";
			case SKIPPED -> "보내지 않음: " + skipText(n.getSkipReason());
		};
	}

	static String skipText(SkipReason reason) {
		return switch (reason) {
			case CHANNEL_OFF -> "안내 문자 기능이 아직 준비 중이에요";
			case LANDLORD_OFF -> "알림 설정에서 껐어요";
			case OPTED_OUT -> "세입자가 안내 문자를 받지 않기로 했어요";
			case LINK_CLOSED_BY_LANDLORD -> "링크를 닫아 안내 문자를 멈췄어요";
			case LEASE_ENDED -> "계약이 끝났어요";
			case STALE -> "그 사이 받을 월세 상태가 바뀌었어요";
			case DAILY_CAP -> "같은 날 이미 안내 문자를 보냈어요";
			case EMAIL_UNVERIFIED -> "이메일을 인증하지 않았어요";
			case NO_ADDRESS -> "받을 주소가 없어요";
		};
	}

}
