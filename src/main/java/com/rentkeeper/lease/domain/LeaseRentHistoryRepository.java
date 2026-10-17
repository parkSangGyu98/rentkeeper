package com.rentkeeper.lease.domain;

import java.util.List;

import org.springframework.data.repository.Repository;

/** 월세 이력 저장소. landlordId 없이 조회할 방법이 없다 (ADR-018) */
public interface LeaseRentHistoryRepository extends Repository<LeaseRentHistory, Long> {

	LeaseRentHistory saveAndFlush(LeaseRentHistory history);

	void delete(LeaseRentHistory history);

	void flush();

	/** 계약의 이력, 적용 시작월 순 */
	List<LeaseRentHistory> findByLeaseIdAndLandlordIdOrderByEffectiveFromAsc(long leaseId, long landlordId);

}
