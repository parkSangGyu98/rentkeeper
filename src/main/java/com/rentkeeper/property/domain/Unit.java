package com.rentkeeper.property.domain;

import java.util.Objects;

import com.rentkeeper.common.jpa.BaseTimeEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 호실 (PROP-2, V13). 건물(property)은 연관관계 없이 id로만 가리킨다: (property_id, landlord_id)
 * 복합 FK가 같은 임대인의 건물만 허용한다 (ADR-018). 상태는 저장하지 않는다(PROP-3).
 */
@Entity
@Table(name = "unit")
public class Unit extends BaseTimeEntity {

	public static final int NAME_MAX_LENGTH = 20;

	public static final int MEMO_MAX_LENGTH = 500;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "landlord_id", nullable = false, updatable = false)
	private long landlordId;

	@Column(name = "property_id", nullable = false, updatable = false)
	private long propertyId;

	@Column(nullable = false, length = NAME_MAX_LENGTH)
	private String name;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private UnitType type;

	@Column(length = MEMO_MAX_LENGTH)
	private String memo;

	protected Unit() {
	}

	/** 건물에 호실을 만든다. 건물이 같은 임대인의 것인지는 호출한 쪽이 먼저 확인한다(DB 복합 FK가 한 번 더 막는다) */
	public static Unit create(Property property, String name, UnitType type, String memo) {
		Unit unit = new Unit();
		unit.landlordId = property.getLandlordId();
		unit.propertyId = Objects.requireNonNull(property.getId(), "property must be saved");
		unit.change(name, type, memo);
		return unit;
	}

	public void change(String name, UnitType type, String memo) {
		this.name = Texts.required(name, NAME_MAX_LENGTH, "unit name");
		this.type = Objects.requireNonNull(type, "type");
		this.memo = Texts.memo(memo, MEMO_MAX_LENGTH);
	}

	public Long getId() {
		return id;
	}

	public long getLandlordId() {
		return landlordId;
	}

	public long getPropertyId() {
		return propertyId;
	}

	public String getName() {
		return name;
	}

	public UnitType getType() {
		return type;
	}

	public String getMemo() {
		return memo;
	}

}
