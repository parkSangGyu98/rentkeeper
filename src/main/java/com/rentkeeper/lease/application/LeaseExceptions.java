package com.rentkeeper.lease.application;

import com.rentkeeper.common.error.BusinessException;
import com.rentkeeper.common.error.ErrorCode;
import com.rentkeeper.lease.application.LeaseViews.ExistingTenant;

/** 계약 등록·수정의 업무 규칙 위반. 화면은 코드에 맞춰 친절한 한글 문구를 고른다 */
public final class LeaseExceptions {

	private LeaseExceptions() {
	}

	/** 같은 호실의 진행 중 계약과 기간이 겹친다 (LEASE-4, ex_lease_unit_active_period) */
	public static class LeaseOverlapException extends BusinessException {

		LeaseOverlapException() {
			super(ErrorCode.LEASE_PERIOD_OVERLAP, "active lease period overlaps");
		}

		LeaseOverlapException(Throwable cause) {
			super(ErrorCode.LEASE_PERIOD_OVERLAP, "active lease period overlaps", cause);
		}

	}

	/**
	 * 같은 연락처의 세입자가 이미 있다. 그 세입자로 계약할지 확인받아야 한다 (M2 결정 6).
	 * <p>
	 * existing이 null이면 동시에 같은 번호로 등록된 경우다(다시 조회해 보여 준다).
	 */
	public static class ExistingTenantException extends BusinessException {

		private final ExistingTenant existing;

		ExistingTenantException(ExistingTenant existing) {
			super(ErrorCode.EXISTING_TENANT, "tenant with same phone exists");
			this.existing = existing;
		}

		public ExistingTenant existing() {
			return existing;
		}

	}

}
