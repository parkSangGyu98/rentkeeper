package com.rentkeeper.lease.application;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.rentkeeper.lease.domain.Lease;
import com.rentkeeper.lease.domain.LeaseRentHistoryRepository;
import com.rentkeeper.lease.domain.LeaseRepository;

/**
 * 청구 모듈(billing)에 계약 사실을 넘긴다 (ADR-024). billing은 lease의 엔티티·저장소를 쓰지 않고 이 서비스만
 * 쓴다.
 */
@Service
public class LeaseBillingService {

	private final LeaseRepository leases;

	private final LeaseRentHistoryRepository rentHistories;

	private final JdbcTemplate jdbcTemplate;

	private final Clock clock;

	LeaseBillingService(LeaseRepository leases, LeaseRentHistoryRepository rentHistories, JdbcTemplate jdbcTemplate,
			Clock clock) {
		this.leases = leases;
		this.rentHistories = rentHistories;
		this.jdbcTemplate = jdbcTemplate;
		this.clock = clock;
	}

	/** 배치가 볼 계약 하나 (임대인 전체를 훑으므로 id 쌍만) */
	public record LeaseKey(long leaseId, long landlordId) {
	}

	/**
	 * 청구가 생길 수 있는 계약 (모든 임대인, id 순): 실제로 끝나는 날이 since보다 뒤인 계약. 배치는 이 목록을 계약마다 따로
	 * 잠그고 다시 확인한다. 저장소 규칙(모든 조회에 landlordId, ADR-018)의 예외라 JdbcTemplate으로 id만 꺼낸다.
	 *
	 * @param since
	 *            누락 복구 하한 달의 1일
	 */
	public List<LeaseKey> billableLeases(LocalDate since) {
		return jdbcTemplate.query("""
				select id, landlord_id from lease
				where coalesce(ended_on, end_date) > ?
				order by id
				""", (rs, rowNum) -> new LeaseKey(rs.getLong("id"), rs.getLong("landlord_id")), since);
	}

	/**
	 * 계약을 잠그고 청구 계산에 필요한 사실을 돌려준다. 호출한 쪽(배치의 계약별 트랜잭션)이 끝날 때까지 같은 계약의 수정·종료는 기다린다.
	 * 지워졌거나 남의 계약이면 빈 값.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public Optional<LeaseBillingTerms> lockForBilling(long leaseId, long landlordId) {
		return leases.lockByIdAndLandlordId(leaseId, landlordId).map(this::terms);
	}

	private LeaseBillingTerms terms(Lease lease) {
		List<LeaseBillingTerms.Rent> rents = rentHistories
				.findByLeaseIdAndLandlordIdOrderByEffectiveFromAsc(lease.getId(), lease.getLandlordId()).stream()
				.map(h -> new LeaseBillingTerms.Rent(h.effectiveMonth(), h.getMonthlyRent(), h.getMaintenanceFee()))
				.toList();
		LocalDate actualEnd = lease.getEndedOn() != null ? lease.getEndedOn() : lease.getEndDate();
		LocalDate registeredOn = LocalDate.ofInstant(lease.getCreatedAt(), clock.getZone());
		return new LeaseBillingTerms(lease.getId(), lease.getLandlordId(), lease.getDueDay(), lease.getStartDate(),
				actualEnd, lease.getIssueDaysBefore(), registeredOn, rents);
	}

}
