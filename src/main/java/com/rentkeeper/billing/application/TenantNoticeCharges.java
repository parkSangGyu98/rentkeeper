package com.rentkeeper.billing.application;

import java.sql.Date;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.rentkeeper.billing.domain.ChargeRepository;
import com.rentkeeper.billing.domain.ChargeStatus;

/**
 * 세입자 알림에 필요한 받을 월세 정보 (M5 결정 4·5). 알림 모듈이 계획할 때와 보낼 때 쓴다. 개인정보는
 * 없다(ID·날짜·상태·금액).
 */
@Service
@Transactional(readOnly = true)
public class TenantNoticeCharges {

	private final JdbcTemplate jdbc;

	private final ChargeRepository charges;

	TenantNoticeCharges(JdbcTemplate jdbc, ChargeRepository charges) {
		this.jdbc = jdbc;
		this.charges = charges;
	}

	/** 안내할 수 있는 받을 월세의 키 */
	public record NoticeTarget(long chargeId, long landlordId, long leaseId, LocalDate dueDate, ChargeStatus status) {
	}

	/** 보낼 때 본문과 상태 확인에 쓰는 값 */
	public record NoticeCharge(long chargeId, YearMonth month, LocalDate dueDate, ChargeStatus status, long total,
			long balance) {
	}

	/**
	 * 납부일이 dueDates 중 하나이고 상태가 statuses 안인 받을 월세 (모든 임대인, 계획 배치용). 납부일 지남 배치와 같이
	 * 상태·납부일 인덱스(ix_charge_status_due_date)로 찾는다.
	 */
	public List<NoticeTarget> targets(Collection<LocalDate> dueDates, Set<ChargeStatus> statuses) {
		if (dueDates.isEmpty() || statuses.isEmpty()) {
			return List.of();
		}
		List<Object> args = new ArrayList<>();
		statuses.forEach(s -> args.add(s.name()));
		dueDates.forEach(d -> args.add(Date.valueOf(d)));
		return jdbc.query(
				"select id, landlord_id, lease_id, due_date, status from charge where status in ("
						+ placeholders(statuses.size()) + ") and due_date in (" + placeholders(dueDates.size())
						+ ") order by landlord_id, lease_id, id",
				(rs, i) -> new NoticeTarget(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getDate(4).toLocalDate(),
						ChargeStatus.valueOf(rs.getString(5))),
				args.toArray());
	}

	/** 그 계약의 받을 월세 (남의 것·다른 계약의 것이면 빈 값) */
	public Optional<NoticeCharge> charge(long landlordId, long leaseId, long chargeId) {
		return charges.findByIdAndLeaseIdAndLandlordId(chargeId, leaseId, landlordId)
				.map(c -> new NoticeCharge(c.getId(), c.getBillingMonth(), c.getDueDate(), c.getStatus(),
						c.getTotalAmount(), c.balance()));
	}

	private static String placeholders(int count) {
		return String.join(", ", java.util.Collections.nCopies(count, "?"));
	}

}
