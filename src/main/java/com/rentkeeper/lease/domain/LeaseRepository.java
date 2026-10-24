package com.rentkeeper.lease.domain;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

/** 계약 저장소. landlordId 없이 조회할 방법이 없다 (ADR-018) */
public interface LeaseRepository extends Repository<Lease, Long> {

	Lease saveAndFlush(Lease lease);

	void delete(Lease lease);

	void flush();

	Optional<Lease> findByIdAndLandlordId(long id, long landlordId);

	/** 계약의 호실 ID (엔티티를 읽지 않고). 수정·종료가 호실 → 계약 순서로 잠그기 전에 쓴다 */
	@Query("select l.unitId from Lease l where l.id = :id and l.landlordId = :landlordId")
	Optional<Long> findUnitIdByIdAndLandlordId(@Param("id") long id, @Param("landlordId") long landlordId);

	/**
	 * 계약 행을 잠그고 가져온다 (청구 생성 배치, ADR-024). 배치가 청구를 만드는 동안 같은 계약의 수정·종료는 기다리고, 수정·종료가
	 * 먼저 잠갔으면 배치가 기다린 뒤 바뀐 조건으로 계산한다.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select l from Lease l where l.id = :id and l.landlordId = :landlordId")
	Optional<Lease> lockByIdAndLandlordId(@Param("id") long id, @Param("landlordId") long landlordId);

	/** 호실의 모든 계약 (종료 포함), 들어오는 날 최근 순 */
	List<Lease> findByUnitIdAndLandlordIdOrderByStartDateDesc(long unitId, long landlordId);

	/** 한 세입자의 계약들 (같은 사람이 여러 호실을 빌린 경우) */
	List<Lease> findByTenantIdAndLandlordId(long tenantId, long landlordId);

	List<Lease> findByLandlordIdAndStatusAndUnitIdIn(long landlordId, LeaseStatus status, Collection<Long> unitIds);

	List<Lease> findByLandlordIdAndIdIn(long landlordId, Collection<Long> ids);

	/** 호실들의 모든 계약 ID (종료 포함, 대시보드의 건물 고르기) */
	@Query("select l.id from Lease l where l.landlordId = :landlordId and l.unitId in :unitIds")
	List<Long> findIdsByLandlordIdAndUnitIdIn(@Param("landlordId") long landlordId,
			@Param("unitIds") Collection<Long> unitIds);

	/** 나가는 날이 until 이하인 진행 중 계약, 나가는 날 순 (대시보드의 곧 끝나는 계약, M4 결정 14) */
	@Query("""
			select l from Lease l where l.landlordId = :landlordId
			  and l.status = com.rentkeeper.lease.domain.LeaseStatus.ACTIVE and l.endDate <= :until
			order by l.endDate, l.id
			""")
	List<Lease> findActiveEndingBy(@Param("landlordId") long landlordId, @Param("until") LocalDate until);

	/** 위와 같고 그 호실들만 (건물 고르기) */
	@Query("""
			select l from Lease l where l.landlordId = :landlordId and l.unitId in :unitIds
			  and l.status = com.rentkeeper.lease.domain.LeaseStatus.ACTIVE and l.endDate <= :until
			order by l.endDate, l.id
			""")
	List<Lease> findActiveEndingByInUnits(@Param("landlordId") long landlordId, @Param("until") LocalDate until,
			@Param("unitIds") Collection<Long> unitIds);

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
