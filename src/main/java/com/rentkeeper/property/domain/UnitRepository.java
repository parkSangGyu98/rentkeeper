package com.rentkeeper.property.domain;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

/**
 * 호실 저장소. 건물 저장소와 같은 규칙: landlordId 없이 조회할 방법이 없다 (ADR-018).
 */
public interface UnitRepository extends Repository<Unit, Long> {

	Unit saveAndFlush(Unit unit);

	void delete(Unit unit);

	void flush();

	Optional<Unit> findByIdAndLandlordId(long id, long landlordId);

	/**
	 * 호실 행을 잠그고 가져온다 (SELECT ... FOR UPDATE). 같은 호실에 계약을 동시에 등록·수정할 때 차례로 처리하게 한다:
	 * 겹침 검사를 정확히 하고, 기간 겹침 EXCLUDE 제약끼리 서로 기다리다 교착(deadlock)되는 것을 막는다 (ADR-019).
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select u from Unit u where u.id = :id and u.landlordId = :landlordId")
	Optional<Unit> lockByIdAndLandlordId(@Param("id") long id, @Param("landlordId") long landlordId);

	List<Unit> findByPropertyIdAndLandlordId(long propertyId, long landlordId);

	List<Unit> findByLandlordId(long landlordId);

	boolean existsByPropertyIdAndLandlordId(long propertyId, long landlordId);

}
