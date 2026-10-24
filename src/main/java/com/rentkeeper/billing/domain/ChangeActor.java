package com.rentkeeper.billing.domain;

/**
 * 청구 상태·항목을 바꾼 주체 (이력의 actor_type, actor_id). 임대인이면 id가 있고 배치(SYSTEM)는 없다.
 */
public record ChangeActor(Type type, Long id) {

	public enum Type {
		LANDLORD, TENANT, SYSTEM
	}

	public static final ChangeActor SYSTEM = new ChangeActor(Type.SYSTEM, null);

	public ChangeActor {
		if (type == null || (type == Type.SYSTEM) != (id == null)) {
			throw new IllegalArgumentException("SYSTEM has no id, others need one");
		}
	}

	public static ChangeActor landlord(long landlordId) {
		return new ChangeActor(Type.LANDLORD, landlordId);
	}

	/** 세입자 (id는 세입자가 쓴 링크의 tenant_access_token.id, M4 결정 12) */
	public static ChangeActor tenant(long tenantLinkId) {
		return new ChangeActor(Type.TENANT, tenantLinkId);
	}

}
