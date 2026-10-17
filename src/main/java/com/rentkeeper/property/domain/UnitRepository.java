package com.rentkeeper.property.domain;

import java.util.List;
import java.util.Optional;

import org.springframework.data.repository.Repository;

/**
 * 호실 저장소. 건물 저장소와 같은 규칙: landlordId 없이 조회할 방법이 없다 (ADR-018).
 */
public interface UnitRepository extends Repository<Unit, Long> {

	Unit saveAndFlush(Unit unit);

	void delete(Unit unit);

	void flush();

	Optional<Unit> findByIdAndLandlordId(long id, long landlordId);

	List<Unit> findByPropertyIdAndLandlordId(long propertyId, long landlordId);

	List<Unit> findByLandlordId(long landlordId);

	boolean existsByPropertyIdAndLandlordId(long propertyId, long landlordId);

}
