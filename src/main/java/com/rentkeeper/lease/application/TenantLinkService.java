package com.rentkeeper.lease.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.rentkeeper.common.crypto.SecureTokens;
import com.rentkeeper.common.error.ResourceNotFoundException;
import com.rentkeeper.lease.application.LeaseExceptions.TenantLinkExpiredException;
import com.rentkeeper.lease.application.LeaseExceptions.TenantLinkStaleException;
import com.rentkeeper.lease.domain.Lease;
import com.rentkeeper.lease.domain.LeaseRepository;
import com.rentkeeper.lease.domain.TenantAccessToken;
import com.rentkeeper.lease.domain.TenantAccessTokenRepository;
import com.rentkeeper.lease.domain.TenantLinkPurpose;
import com.rentkeeper.lease.domain.TenantLinkRevokeReason;

/**
 * 세입자 링크 만들기·새로 만들기·닫기 (TEN-1, M4 결정 1~4, ADR-026)와 알림용 링크 (M5 결정 7·8,
 * ADR-029). 남의 계약이면 404.
 * <ul>
 * <li>원문은 만든 직후 돌려주기만 하고 저장하지 않는다(해시만). 다시 보여 줄 방법이 없다</li>
 * <li>화면이 보던 열린 링크 ID와 지금 열린 링크가 같을 때만 바꾼다. 다르면 아무것도 바꾸지 않는다(새로고침으로 다시 온 POST가
 * 방금 만든 링크를 닫지 않게)</li>
 * <li>계약 행을 잠근 뒤 비교한다. 같은 순간 두 요청이 와도 하나만 바꾼다(DB 부분 UNIQUE가 한 번 더 막는다)</li>
 * <li>링크는 두 유형이다: 임대인이 직접 만든 링크(LANDLORD)와 알림을 보낼 때 만드는 링크(NOTIFICATION).
 * 계약·유형마다 열린 링크는 하나다. 알림 링크를 바꿔도 임대인 링크는 그대로다</li>
 * <li>[링크 닫기]는 두 유형을 모두 닫고 그 계약의 세입자 알림을 멈춘다. [알림 다시 켜기]로만 다시 시작한다</li>
 * </ul>
 */
@Service
@Transactional(readOnly = true)
public class TenantLinkService {

	private final LeaseRepository leases;

	private final TenantAccessTokenRepository tokens;

	private final Clock clock;

	TenantLinkService(LeaseRepository leases, TenantAccessTokenRepository tokens, Clock clock) {
		this.leases = leases;
		this.tokens = tokens;
		this.clock = clock;
	}

	/**
	 * 새로 만든 링크의 원문. 응답 화면이나 보낼 알림 본문에만 쓴다(DB·flash·세션·로그에 넣지 않는다).
	 *
	 * @param usableUntil
	 *            이 링크를 쓸 수 있는 마지막 날
	 */
	public record IssuedLink(String rawToken, LocalDate usableUntil) {

		// 원문이 로그·예외 메시지에 남지 않게 한다
		@Override
		public String toString() {
			return "IssuedLink[usableUntil=" + usableUntil + "]";
		}

	}

	/**
	 * 계약 상세의 세입자 링크 구역.
	 *
	 * @param openLinkId
	 *            임대인이 만든 열린 링크 ID (없으면 null). 폼의 숨은 칸으로 보낸다
	 * @param createdOn
	 *            열린 링크를 만든 날 (서울)
	 * @param lastUsedOn
	 *            세입자가 마지막으로 연 날 (서울, 아직 안 열었으면 null)
	 * @param usableUntil
	 *            링크를 쓸 수 있는 마지막 날 (계약의 실제 끝 + 90일)
	 * @param withinPeriod
	 *            오늘이 쓸 수 있는 기간 안인지 (밖이면 새 링크를 만들 수 없고, 열린 링크도 세입자가 열 수 없다)
	 * @param notificationLinkOpen
	 *            알림에 넣은 링크가 열려 있는지 (주소는 알 수 없다)
	 * @param notificationsPaused
	 *            임대인이 [링크 닫기]로 세입자 알림을 멈췄는지
	 */
	public record TenantLinkStatus(Long openLinkId, LocalDate createdOn, LocalDate lastUsedOn, LocalDate usableUntil,
			boolean withinPeriod, boolean notificationLinkOpen, boolean notificationsPaused) {

		public boolean open() {
			return openLinkId != null;
		}

		/** [링크 닫기]를 보일지: 임대인 링크나 알림 링크 중 하나라도 열려 있을 때 */
		public boolean closable() {
			return open() || notificationLinkOpen;
		}

		/** 폼의 숨은 칸 값 (열린 링크가 없으면 빈 값) */
		public String openLinkIdParam() {
			return openLinkId == null ? "" : openLinkId.toString();
		}

	}

	/** 알림용 링크를 만들 수 없는 이유 */
	public enum NotificationLinkUnavailable {

		/** 임대인이 [링크 닫기]로 이 계약의 세입자 알림을 멈춤 */
		PAUSED_BY_LANDLORD,

		/** 계약이 끝나고 90일이 지남 (또는 계약이 없어짐) */
		PERIOD_OVER

	}

	/**
	 * 알림용 링크 만들기 결과. 둘 중 하나만 있다.
	 *
	 * @param link
	 *            새로 만든 알림 링크
	 * @param unavailable
	 *            만들지 않은 이유
	 */
	public record NotificationLink(IssuedLink link, NotificationLinkUnavailable unavailable) {

		@Override
		public String toString() {
			return "NotificationLink[issued=" + (link != null) + ", unavailable=" + unavailable + "]";
		}

	}

	public TenantLinkStatus status(long landlordId, long leaseId) {
		Lease lease = leases.findByIdAndLandlordId(leaseId, landlordId)
				.orElseThrow(() -> new ResourceNotFoundException("lease"));
		Optional<LocalDate> until = TenantAccessToken.usableUntil(lease.getEndDate(), lease.getEndedOn());
		boolean withinPeriod = until.map(last -> !today().isAfter(last)).orElse(false);
		boolean notificationLinkOpen = open(landlordId, leaseId, TenantLinkPurpose.NOTIFICATION).isPresent();
		boolean paused = lease.tenantNotificationsPaused();
		return open(landlordId, leaseId, TenantLinkPurpose.LANDLORD)
				.map(t -> new TenantLinkStatus(t.getId(), localDate(t.getCreatedAt()), localDate(t.getLastUsedAt()),
						until.orElse(null), withinPeriod, notificationLinkOpen, paused))
				.orElse(new TenantLinkStatus(null, null, null, until.orElse(null), withinPeriod, notificationLinkOpen,
						paused));
	}

	private LocalDate localDate(Instant at) {
		return at == null ? null : LocalDate.ofInstant(at, clock.getZone());
	}

	/**
	 * 임대인 링크를 새로 만든다. 열린 임대인 링크가 있으면 먼저 닫는다. 알림 링크와 알림 멈춤은 건드리지 않는다.
	 *
	 * @param seenOpenLinkId
	 *            화면이 보던 열린 링크 ID (없었으면 null)
	 * @throws TenantLinkStaleException
	 *             화면이 보던 뒤로 링크가 바뀌었을 때 (아무것도 바꾸지 않음)
	 * @throws TenantLinkExpiredException
	 *             링크를 쓸 수 있는 기간이 지났을 때
	 */
	@Transactional
	public IssuedLink issue(long landlordId, long leaseId, Long seenOpenLinkId) {
		Lease lease = lockOwned(landlordId, leaseId);
		Optional<TenantAccessToken> open = currentOpen(landlordId, leaseId, seenOpenLinkId);
		LocalDate until = usableUntil(lease).orElseThrow(TenantLinkExpiredException::new);
		open.ifPresent(token -> revoke(token, TenantLinkRevokeReason.LANDLORD_REISSUED));
		return create(landlordId, leaseId, TenantLinkPurpose.LANDLORD, until);
	}

	/**
	 * 임대인 링크와 알림 링크를 모두 닫고, 이 계약의 세입자 알림을 멈춘다 (M5 결정 8). 다시 보내려면
	 * {@link #resumeNotifications}.
	 *
	 * @throws TenantLinkStaleException
	 *             화면이 보던 링크가 이미 바뀌었거나, 닫을 링크가 하나도 없을 때
	 */
	@Transactional
	public void close(long landlordId, long leaseId, Long seenOpenLinkId) {
		Lease lease = lockOwned(landlordId, leaseId);
		Optional<TenantAccessToken> landlordLink = currentOpen(landlordId, leaseId, seenOpenLinkId);
		Optional<TenantAccessToken> notificationLink = open(landlordId, leaseId, TenantLinkPurpose.NOTIFICATION);
		if (landlordLink.isEmpty() && notificationLink.isEmpty()) {
			throw new TenantLinkStaleException();
		}
		landlordLink.ifPresent(token -> revoke(token, TenantLinkRevokeReason.LANDLORD_CLOSED));
		notificationLink.ifPresent(token -> revoke(token, TenantLinkRevokeReason.LANDLORD_CLOSED));
		lease.pauseTenantNotifications(clock.instant());
		leases.flush();
	}

	/** [알림 다시 켜기] (M5 결정 8). 링크는 다시 열지 않는다: 다음 알림을 보낼 때 새 알림 링크를 만든다 */
	@Transactional
	public void resumeNotifications(long landlordId, long leaseId) {
		lockOwned(landlordId, leaseId).resumeTenantNotifications();
		leases.flush();
	}

	/**
	 * 알림에 넣을 새 링크를 만든다 (M5 결정 7, 알림을 실제로 보내는 시점에만 부른다). 열린 알림 링크가 있으면 닫는다. 임대인 링크는
	 * 그대로 둔다. 알림이 멈췄거나 기간이 지났으면 만들지 않는다.
	 */
	@Transactional
	public NotificationLink issueForNotification(long landlordId, long leaseId) {
		Optional<Lease> found = leases.lockByIdAndLandlordId(leaseId, landlordId);
		if (found.isEmpty()) {
			return new NotificationLink(null, NotificationLinkUnavailable.PERIOD_OVER);
		}
		Lease lease = found.get();
		if (lease.tenantNotificationsPaused()) {
			return new NotificationLink(null, NotificationLinkUnavailable.PAUSED_BY_LANDLORD);
		}
		Optional<LocalDate> until = usableUntil(lease);
		if (until.isEmpty()) {
			return new NotificationLink(null, NotificationLinkUnavailable.PERIOD_OVER);
		}
		open(landlordId, leaseId, TenantLinkPurpose.NOTIFICATION)
				.ifPresent(token -> revoke(token, TenantLinkRevokeReason.NOTIFICATION_ROTATED));
		return new NotificationLink(create(landlordId, leaseId, TenantLinkPurpose.NOTIFICATION, until.get()), null);
	}

	/** 알림을 보내기 전에 링크를 만들 수 있는지만 본다 (잠그지 않음, 만들지 않음) */
	public Optional<NotificationLinkUnavailable> notificationLinkUnavailable(long landlordId, long leaseId) {
		Optional<Lease> lease = leases.findByIdAndLandlordId(leaseId, landlordId);
		if (lease.isEmpty() || usableUntil(lease.get()).isEmpty()) {
			return Optional.of(NotificationLinkUnavailable.PERIOD_OVER);
		}
		return lease.get().tenantNotificationsPaused()
				? Optional.of(NotificationLinkUnavailable.PAUSED_BY_LANDLORD)
				: Optional.empty();
	}

	private IssuedLink create(long landlordId, long leaseId, TenantLinkPurpose purpose, LocalDate until) {
		String raw = SecureTokens.newToken();
		tokens.saveAndFlush(TenantAccessToken.open(landlordId, leaseId, SecureTokens.sha256Hex(raw), purpose));
		return new IssuedLink(raw, until);
	}

	private void revoke(TenantAccessToken token, TenantLinkRevokeReason reason) {
		token.revoke(clock.instant(), reason);
		// 부분 UNIQUE(계약·유형마다 열린 링크 하나) 때문에 새 링크를 넣기 전에 먼저 반영한다
		tokens.flush();
	}

	/** 오늘 쓸 수 있으면 마지막 날, 기간이 지났으면 빈 값 */
	private Optional<LocalDate> usableUntil(Lease lease) {
		return TenantAccessToken.usableUntil(lease.getEndDate(), lease.getEndedOn())
				.filter(last -> !today().isAfter(last));
	}

	private Lease lockOwned(long landlordId, long leaseId) {
		return leases.lockByIdAndLandlordId(leaseId, landlordId)
				.orElseThrow(() -> new ResourceNotFoundException("lease"));
	}

	private Optional<TenantAccessToken> open(long landlordId, long leaseId, TenantLinkPurpose purpose) {
		return tokens.findByLeaseIdAndLandlordIdAndPurposeAndRevokedAtIsNull(leaseId, landlordId, purpose);
	}

	/** 지금 열린 임대인 링크. 화면이 보던 것과 다르면 예외 */
	private Optional<TenantAccessToken> currentOpen(long landlordId, long leaseId, Long seenOpenLinkId) {
		Optional<TenantAccessToken> open = open(landlordId, leaseId, TenantLinkPurpose.LANDLORD);
		if (!Objects.equals(open.map(TenantAccessToken::getId).orElse(null), seenOpenLinkId)) {
			throw new TenantLinkStaleException();
		}
		return open;
	}

	private LocalDate today() {
		return LocalDate.now(clock);
	}

}
