package com.rentkeeper.lease.domain;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** 계약 저장소. landlordId 없이 조회할 방법이 없다 (ADR-018) */
public interface LeaseRepository extends Repository<Lease, Long> {

	Lease saveAndFlush(Lease lease);

	void delete(Lease lease);

	void flush();

	Optional<Lease> findByIdAndLandlordId(long id, long landlordId);

	/** 호실의 모든 계약 (종료 포함), 들어오는 날 최근 순 */
	List<Lease> findByUnitIdAndLandlordIdOrderByStartDateDesc(long unitId, long landlordId);

	List<Lease> findByLandlordIdAndStatusAndUnitIdIn(long landlordId, LeaseStatus status, Collection<Long> unitIds);

	/** 같은 임대인의 가장 최근 계약 (계좌 미리 채우기, M2 결정 20) */
	Optional<Lease> findFirstByLandlordIdOrderByIdDesc(long landlordId);

	/**
	 * 같은 호실의 ACTIVE 계약 중 [start, end)와 겹치는 것이 있는지 (DB의 ex_lease_unit_active_period와
	 * 같은 규칙). 친절한 안내를 위해 먼저 확인하고, 동시 요청은 DB 제약이 막는다.
	 *
	 * @param excludeLeaseId
	 *            수정 중인 자기 자신 (새 계약이면 0)
	 */
	@Query("""
			select count(l) > 0 from Lease l
			where l.landlordId = :landlordId and l.unitId = :unitId and l.status = com.rentkeeper.lease.domain.LeaseStatus.ACTIVE
			  and l.id <> :excludeLeaseId and l.startDate < :end and :start < l.endDate
			""")
	boolean existsActiveOverlap(@Param("landlordId") long landlordId, @Param("unitId") long unitId,
			@Param("start") LocalDate start, @Param("end") LocalDate end, @Param("excludeLeaseId") long excludeLeaseId);

}
