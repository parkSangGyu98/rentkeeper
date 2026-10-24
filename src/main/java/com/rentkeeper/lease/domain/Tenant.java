package com.rentkeeper.lease.domain;

import java.time.Instant;
import java.util.Objects;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.rentkeeper.common.jpa.BaseTimeEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 세입자 (V14). 이름·연락처·메모는 암호화해 저장한다(ADR-017). 같은 임대인 안에서 연락처가 같으면 같은 세입자다(M2 결정
 * 6) → phoneHash(HMAC)로 찾는다. 보관 기간이 미정이라 따로 지우지 않는다(M2 결정 19).
 */
@Entity
@Table(name = "tenant")
public class Tenant extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "landlord_id", nullable = false, updatable = false)
	private long landlordId;

	@Convert(converter = LeaseConverters.TenantName.class)
	@Column(name = "name_enc", nullable = false)
	private PersonName name;

	@Convert(converter = LeaseConverters.TenantPhone.class)
	@Column(name = "phone_enc", nullable = false)
	private PhoneNumber phone;

	// DB는 char(64) (HMAC hex 고정 길이)
	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(name = "phone_hash", nullable = false, length = 64)
	private String phoneHash;

	@Convert(converter = LeaseConverters.TenantMemo.class)
	@Column(name = "memo_enc")
	private PrivateMemo memo;

	// NULL=안내 문자를 받음 (M5 결정 9). 토큰이 아니라 세입자에 두어 알림 링크가 바뀌어도 유지된다
	@Column(name = "notify_opt_out_at")
	private Instant notifyOptOutAt;

	protected Tenant() {
	}

	/**
	 * @param phoneHash
	 *            연락처의 HMAC (KeyedHasher). 엔티티는 키를 모르므로 서비스가 계산해 넘긴다
	 */
	public static Tenant register(long landlordId, PersonName name, PhoneNumber phone, String phoneHash,
			PrivateMemo memo) {
		Tenant tenant = new Tenant();
		tenant.landlordId = landlordId;
		tenant.change(name, phone, phoneHash, memo);
		return tenant;
	}

	public void change(PersonName name, PhoneNumber phone, String phoneHash, PrivateMemo memo) {
		this.name = Objects.requireNonNull(name, "name");
		this.phone = Objects.requireNonNull(phone, "phone");
		if (phoneHash == null || !phoneHash.matches("[0-9a-f]{64}")) {
			throw new IllegalArgumentException("phoneHash must be 64 lowercase hex");
		}
		this.phoneHash = phoneHash;
		this.memo = memo;
	}

	/**
	 * 세입자가 안내 문자를 그만 받기로 한다 (TEN-6). 이미 거부한 상태면 처음 거부한 시각을 그대로 둔다(멱등).
	 *
	 * @return 이번에 바뀌었으면 true
	 */
	public boolean optOutOfNotifications(Instant now) {
		if (notifyOptOutAt != null) {
			return false;
		}
		notifyOptOutAt = Objects.requireNonNull(now, "now");
		return true;
	}

	/**
	 * 세입자가 안내 문자를 다시 받기로 한다. 이미 받는 상태면 바꾸지 않는다(멱등).
	 *
	 * @return 이번에 바뀌었으면 true
	 */
	public boolean optInToNotifications() {
		if (notifyOptOutAt == null) {
			return false;
		}
		notifyOptOutAt = null;
		return true;
	}

	public boolean notificationsOptedOut() {
		return notifyOptOutAt != null;
	}

	public Instant getNotifyOptOutAt() {
		return notifyOptOutAt;
	}

	public Long getId() {
		return id;
	}

	public long getLandlordId() {
		return landlordId;
	}

	public PersonName getName() {
		return name;
	}

	public PhoneNumber getPhone() {
		return phone;
	}

	public String getPhoneHash() {
		return phoneHash;
	}

	public PrivateMemo getMemo() {
		return memo;
	}

	// 엔티티를 로그에 찍어도 개인정보가 나가지 않게 한다
	@Override
	public String toString() {
		return "Tenant[id=" + id + ", name=" + name + ", phone=" + phone + "]";
	}

}
