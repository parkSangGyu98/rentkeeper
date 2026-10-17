package com.rentkeeper.property.domain;

import java.util.List;
import java.util.Optional;

import org.springframework.data.repository.Repository;

/**
 * 건물 저장소. <strong>{@code JpaRepository}를 상속하지 않는다</strong>:
 * {@code findById}·{@code findAll}이 없어 landlordId 없이 조회할 방법이 없다. 조회 메서드는 모두
 * landlordId를 받는다 (ADR-018, OwnedRepositoryConventionTest가 검사).
 */
public interface PropertyRepository extends Repository<Property, Long> {

	Property saveAndFlush(Property property);

	void delete(Property property);

	void flush();

	Optional<Property> findByIdAndLandlordId(long id, long landlordId);

	List<Property> findByLandlordId(long landlordId);

}
