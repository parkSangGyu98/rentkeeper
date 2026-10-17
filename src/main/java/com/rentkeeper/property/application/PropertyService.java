package com.rentkeeper.property.application;

import java.text.Collator;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.rentkeeper.common.error.ResourceNotFoundException;
import com.rentkeeper.common.jpa.Constraints;
import com.rentkeeper.property.application.PropertyExceptions.DuplicatePropertyNameException;
import com.rentkeeper.property.application.PropertyExceptions.DuplicateUnitNameException;
import com.rentkeeper.property.application.PropertyExceptions.PropertyHasUnitsException;
import com.rentkeeper.property.application.PropertyExceptions.UnitHasLeasesException;
import com.rentkeeper.property.application.PropertyViews.PropertyDetail;
import com.rentkeeper.property.application.PropertyViews.PropertyInput;
import com.rentkeeper.property.application.PropertyViews.PropertySummary;
import com.rentkeeper.property.application.PropertyViews.UnitInput;
import com.rentkeeper.property.application.PropertyViews.UnitRow;
import com.rentkeeper.property.application.PropertyViews.UnitView;
import com.rentkeeper.property.domain.Property;
import com.rentkeeper.property.domain.PropertyRepository;
import com.rentkeeper.property.domain.Unit;
import com.rentkeeper.property.domain.UnitRepository;
import com.rentkeeper.property.domain.UnitStatus;

/**
 * 건물·호실 관리 (PROP-1~3). 모든 공개 메서드는 첫 인자로 로그인한 임대인의 landlordId를 받고, 남의 것이거나 없는
 * 리소스는 같은 404(ResourceNotFoundException)로 끝낸다 (ADR-018).
 */
@Service
@Transactional(readOnly = true)
public class PropertyService {

	static final String PROPERTY_NAME_UNIQUE = "uk_property_landlord_name";

	static final String UNIT_NAME_UNIQUE = "uk_unit_property_name";

	static final String UNIT_PROPERTY_FK = "fk_unit_property";

	static final String LEASE_UNIT_FK = "fk_lease_unit";

	private static final Collator KOREAN = Collator.getInstance(Locale.KOREAN);

	private final PropertyRepository properties;

	private final UnitRepository units;

	private final ObjectProvider<UnitOccupancy> occupancy;

	public PropertyService(PropertyRepository properties, UnitRepository units,
			ObjectProvider<UnitOccupancy> occupancy) {
		this.properties = properties;
		this.units = units;
		this.occupancy = occupancy;
	}

	// ---------------------------------------------------------------- 건물

	public List<PropertySummary> list(long landlordId) {
		List<Unit> all = units.findByLandlordId(landlordId);
		Map<Long, UnitStatus> statuses = statusOf(landlordId, all.stream().map(Unit::getId).toList());
		Map<Long, List<Unit>> byProperty = all.stream().collect(Collectors.groupingBy(Unit::getPropertyId));
		return properties.findByLandlordId(landlordId).stream().sorted(Comparator.comparing(Property::getName, KOREAN))
				.map(property -> {
					List<Unit> propertyUnits = byProperty.getOrDefault(property.getId(), List.of());
					int vacant = (int) propertyUnits.stream().filter(u -> status(statuses, u) == UnitStatus.VACANT)
							.count();
					int occupied = (int) propertyUnits.stream().filter(u -> status(statuses, u) == UnitStatus.OCCUPIED)
							.count();
					return new PropertySummary(property.getId(), property.getName(), property.getAddress(),
							propertyUnits.size(), vacant, occupied);
				}).toList();
	}

	public PropertyDetail detail(long landlordId, long propertyId) {
		Property property = ownedProperty(landlordId, propertyId);
		List<Unit> propertyUnits = units.findByPropertyIdAndLandlordId(propertyId, landlordId);
		Map<Long, UnitStatus> statuses = statusOf(landlordId, propertyUnits.stream().map(Unit::getId).toList());
		List<UnitRow> rows = propertyUnits.stream().sorted(Comparator.comparing(Unit::getName, KOREAN))
				.map(u -> new UnitRow(u.getId(), u.getName(), u.getType(), u.getMemo(), status(statuses, u))).toList();
		return new PropertyDetail(property.getId(), property.getName(), property.getAddress(), property.getMemo(),
				rows);
	}

	/** 수정 화면에 채울 현재 값 */
	public PropertyInput currentInput(long landlordId, long propertyId) {
		Property property = ownedProperty(landlordId, propertyId);
		return new PropertyInput(property.getName(), property.getAddress(), property.getMemo());
	}

	/**
	 * @throws DuplicatePropertyNameException
	 *             같은 이름의 건물이 이미 있을 때
	 */
	@Transactional
	public long create(long landlordId, PropertyInput input) {
		Property property = Property.create(landlordId, input.name(), input.address(), input.memo());
		try {
			return properties.saveAndFlush(property).getId();
		} catch (DataIntegrityViolationException e) {
			throw translate(e);
		}
	}

	@Transactional
	public void update(long landlordId, long propertyId, PropertyInput input) {
		Property property = ownedProperty(landlordId, propertyId);
		property.change(input.name(), input.address(), input.memo());
		try {
			properties.flush();
		} catch (DataIntegrityViolationException e) {
			throw translate(e);
		}
	}

	/**
	 * @throws PropertyHasUnitsException
	 *             호실이 있을 때
	 */
	@Transactional
	public void delete(long landlordId, long propertyId) {
		Property property = ownedProperty(landlordId, propertyId);
		if (units.existsByPropertyIdAndLandlordId(propertyId, landlordId)) {
			throw new PropertyHasUnitsException();
		}
		try {
			properties.delete(property);
			properties.flush();
		} catch (DataIntegrityViolationException e) {
			// 확인과 삭제 사이에 다른 창에서 호실을 추가한 경우
			if (Constraints.isViolated(e, UNIT_PROPERTY_FK)) {
				throw new PropertyHasUnitsException();
			}
			throw e;
		}
	}

	// ---------------------------------------------------------------- 호실

	/**
	 * @throws DuplicateUnitNameException
	 *             같은 건물 안에 같은 이름의 호실이 있을 때
	 */
	@Transactional
	public long addUnit(long landlordId, long propertyId, UnitInput input) {
		Property property = ownedProperty(landlordId, propertyId);
		Unit unit = Unit.create(property, input.name(), input.type(), input.memo());
		try {
			return units.saveAndFlush(unit).getId();
		} catch (DataIntegrityViolationException e) {
			throw translate(e);
		}
	}

	/** 다른 모듈(계약)도 호실 소유권 확인에 쓴다. 남의 호실이면 404 */
	public UnitView unit(long landlordId, long unitId) {
		Unit unit = ownedUnit(landlordId, unitId);
		Property property = ownedProperty(landlordId, unit.getPropertyId());
		return new UnitView(unit.getId(), property.getId(), property.getName(), unit.getName(), unit.getType(),
				unit.getMemo());
	}

	/**
	 * 호실을 잠그고 가져온다 (계약 모듈이 같은 호실의 계약을 등록·수정할 때). 호출한 쪽의 트랜잭션이 끝날 때까지 같은 호실의 다른 요청은
	 * 기다린다. 남의 호실이면 404.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public UnitView lockUnit(long landlordId, long unitId) {
		Unit unit = units.lockByIdAndLandlordId(unitId, landlordId)
				.orElseThrow(() -> new ResourceNotFoundException("unit"));
		Property property = ownedProperty(landlordId, unit.getPropertyId());
		return new UnitView(unit.getId(), property.getId(), property.getName(), unit.getName(), unit.getType(),
				unit.getMemo());
	}

	/** 여러 호실의 표시 정보 (계약 목록 등). 남의 호실 ID는 결과에서 빠진다 */
	public Map<Long, UnitView> units(long landlordId, Collection<Long> unitIds) {
		Map<Long, Property> owned = properties.findByLandlordId(landlordId).stream()
				.collect(Collectors.toMap(Property::getId, Function.identity()));
		return units.findByLandlordId(landlordId).stream().filter(u -> unitIds.contains(u.getId()))
				.collect(Collectors.toMap(Unit::getId, u -> {
					Property p = owned.get(u.getPropertyId());
					return new UnitView(u.getId(), p.getId(), p.getName(), u.getName(), u.getType(), u.getMemo());
				}));
	}

	@Transactional
	public void updateUnit(long landlordId, long unitId, UnitInput input) {
		Unit unit = ownedUnit(landlordId, unitId);
		unit.change(input.name(), input.type(), input.memo());
		try {
			units.flush();
		} catch (DataIntegrityViolationException e) {
			throw translate(e);
		}
	}

	/** @return 호실이 있던 건물 ID (삭제 뒤 돌아갈 곳) */
	@Transactional
	public long deleteUnit(long landlordId, long unitId) {
		Unit unit = ownedUnit(landlordId, unitId);
		try {
			units.delete(unit);
			units.flush();
		} catch (DataIntegrityViolationException e) {
			// 계약 기록이 있는 호실 (lease가 property에 기댈 수 없어 미리 묻지 않고 DB FK로 확인한다)
			if (Constraints.isViolated(e, LEASE_UNIT_FK)) {
				throw new UnitHasLeasesException(e);
			}
			throw e;
		}
		return unit.getPropertyId();
	}

	// ---------------------------------------------------------------- 내부

	private Property ownedProperty(long landlordId, long propertyId) {
		return properties.findByIdAndLandlordId(propertyId, landlordId)
				.orElseThrow(() -> new ResourceNotFoundException("property"));
	}

	private Unit ownedUnit(long landlordId, long unitId) {
		return units.findByIdAndLandlordId(unitId, landlordId).orElseThrow(() -> new ResourceNotFoundException("unit"));
	}

	private Map<Long, UnitStatus> statusOf(long landlordId, List<Long> unitIds) {
		if (unitIds.isEmpty()) {
			return Map.of();
		}
		UnitOccupancy provider = occupancy.getIfAvailable();
		return provider == null ? Map.of() : provider.statusOf(landlordId, unitIds);
	}

	private static UnitStatus status(Map<Long, UnitStatus> statuses, Unit unit) {
		return statuses.getOrDefault(unit.getId(), UnitStatus.VACANT);
	}

	private static RuntimeException translate(DataIntegrityViolationException e) {
		if (Constraints.isViolated(e, PROPERTY_NAME_UNIQUE)) {
			return new DuplicatePropertyNameException(e);
		}
		if (Constraints.isViolated(e, UNIT_NAME_UNIQUE)) {
			return new DuplicateUnitNameException(e);
		}
		return e;
	}

}
