package com.rentkeeper.property.domain;

import com.rentkeeper.common.jpa.BaseTimeEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 건물 (PROP-1, V13). 임대인 소유 데이터: 모든 조회·수정은 landlordId로 소유권을 확인한다 (ADR-018).
 */
@Entity
@Table(name = "property")
public class Property extends BaseTimeEntity {

	public static final int NAME_MAX_LENGTH = 50;

	public static final int ADDRESS_MAX_LENGTH = 200;

	public static final int MEMO_MAX_LENGTH = 500;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "landlord_id", nullable = false, updatable = false)
	private long landlordId;

	@Column(nullable = false, length = NAME_MAX_LENGTH)
	private String name;

	@Column(length = ADDRESS_MAX_LENGTH)
	private String address;

	@Column(length = MEMO_MAX_LENGTH)
	private String memo;

	protected Property() {
	}

	public static Property create(long landlordId, String name, String address, String memo) {
		Property property = new Property();
		property.landlordId = landlordId;
		property.change(name, address, memo);
		return property;
	}

	/** 이름·주소·메모를 바꾼다. 이름은 공백을 정리해 1~50자 */
	public void change(String name, String address, String memo) {
		this.name = Texts.required(name, NAME_MAX_LENGTH, "property name");
		this.address = Texts.optional(address, ADDRESS_MAX_LENGTH, "address");
		this.memo = Texts.memo(memo, MEMO_MAX_LENGTH);
	}

	public Long getId() {
		return id;
	}

	public long getLandlordId() {
		return landlordId;
	}

	public String getName() {
		return name;
	}

	public String getAddress() {
		return address;
	}

	public String getMemo() {
		return memo;
	}

}
