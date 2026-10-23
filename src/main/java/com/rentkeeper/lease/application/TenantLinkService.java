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

/**
 * 세입자 링크 만들기·새로 만들기·닫기 (TEN-1, M4 결정 1~4, ADR-026). 남의 계약이면 404.
 * <ul>
 * <li>원문은 만든 직후 돌려주기만 하고 저장하지 않는다(해시만). 다시 보여 줄 방법이 없다</li>
 * <li>화면이 보던 열린 링크 ID와 지금 열린 링크가 같을 때만 바꾼다. 다르면 아무것도 바꾸지 않는다(새로고침으로 다시 온 POST가
 * 방금 만든 링크를 닫지 않게)</li>
 * <li>계약 행을 잠근 뒤 비교한다. 같은 순간 두 요청이 와도 하나만 바꾼다(DB 부분 UNIQUE가 한 번 더 막는다)</li>
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
	 * 새로 만든 링크의 원문. 응답 화면에만 쓴다(flash·세션·로그에 넣지 않는다).
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
	 *            열린 링크 ID (없으면 null). 폼의 숨은 칸으로 보낸다
	 * @param createdOn
	 *            열린 링크를 만든 날 (서울)
	 * @param lastUsedOn
	 *            세입자가 마지막으로 연 날 (서울, 아직 안 열었으면 null)
	 * @param usableUntil
	 *            링크를 쓸 수 있는 마지막 날 (계약의 실제 끝 + 90일)
	 * @param withinPeriod
	 *            오늘이 쓸 수 있는 기간 안인지 (밖이면 새 링크를 만들 수 없고, 열린 링크도 세입자가 열 수 없다)
	 */
	public record TenantLinkStatus(Long openLinkId, LocalDate createdOn, LocalDate lastUsedOn, LocalDate usableUntil,
			boolean withinPeriod) {

		public boolean open() {
			return openLinkId != null;
		}

		/** 폼의 숨은 칸 값 (열린 링크가 없으면 빈 값) */
		public String openLinkIdParam() {
			return openLinkId == null ? "" : openLinkId.toString();
		}

	}

	public TenantLinkStatus status(long landlordId, long leaseId) {
		Lease lease = leases.findByIdAndLandlordId(leaseId, landlordId)
				.orElseThrow(() -> new ResourceNotFoundException("lease"));
		Optional<LocalDate> until = TenantAccessToken.usableUntil(lease.getEndDate(), lease.getEndedOn());
		boolean withinPeriod = until.map(last -> !today().isAfter(last)).orElse(false);
		return tokens.findByLeaseIdAndLandlordIdAndRevokedAtIsNull(leaseId, landlordId)
				.map(t -> new TenantLinkStatus(t.getId(), localDate(t.getCreatedAt()), localDate(t.getLastUsedAt()),
						until.orElse(null), withinPeriod))
				.orElse(new TenantLinkStatus(null, null, null, until.orElse(null), withinPeriod));
	}

	private LocalDate localDate(Instant at) {
		return at == null ? null : LocalDate.ofInstant(at, clock.getZone());
	}

	/**
	 * 새 링크를 만든다. 열린 링크가 있으면 먼저 닫는다.
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
		LocalDate until = TenantAccessToken.usableUntil(lease.getEndDate(), lease.getEndedOn())
				.filter(last -> !today().isAfter(last)).orElseThrow(TenantLinkExpiredException::new);
		Instant now = clock.instant();
		open.ifPresent(token -> {
			token.revoke(now);
			// 부분 UNIQUE(열린 링크 하나) 때문에 새 링크를 넣기 전에 먼저 반영한다
			tokens.flush();
		});
		String raw = SecureTokens.newToken();
		tokens.saveAndFlush(TenantAccessToken.open(landlordId, leaseId, SecureTokens.sha256Hex(raw)));
		return new IssuedLink(raw, until);
	}

	/**
	 * 열린 링크를 닫는다.
	 *
	 * @throws TenantLinkStaleException
	 *             화면이 보던 링크가 이미 바뀌었거나 닫혔을 때
	 */
	@Transactional
	public void close(long landlordId, long leaseId, Long seenOpenLinkId) {
		lockOwned(landlordId, leaseId);
		TenantAccessToken open = currentOpen(landlordId, leaseId, seenOpenLinkId)
				.orElseThrow(TenantLinkStaleException::new);
		open.revoke(clock.instant());
		tokens.flush();
	}

	private Lease lockOwned(long landlordId, long leaseId) {
		return leases.lockByIdAndLandlordId(leaseId, landlordId)
				.orElseThrow(() -> new ResourceNotFoundException("lease"));
	}

	/** 지금 열린 링크. 화면이 보던 것과 다르면 예외 */
	private Optional<TenantAccessToken> currentOpen(long landlordId, long leaseId, Long seenOpenLinkId) {
		Optional<TenantAccessToken> open = tokens.findByLeaseIdAndLandlordIdAndRevokedAtIsNull(leaseId, landlordId);
		if (!Objects.equals(open.map(TenantAccessToken::getId).orElse(null), seenOpenLinkId)) {
			throw new TenantLinkStaleException();
		}
		return open;
	}

	private LocalDate today() {
		return LocalDate.now(clock);
	}

}
