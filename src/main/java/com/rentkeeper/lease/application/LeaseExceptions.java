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

	/** 종료한 계약은 고칠 수 없다 */
	public static class LeaseEndedException extends BusinessException {

		LeaseEndedException() {
			super(ErrorCode.LEASE_ENDED, "lease already ended");
		}

	}

	/** 세입자 정보를 고칠 때, 바꾼 번호를 같은 임대인의 다른 세입자가 이미 쓴다 (uk_tenant_landlord_phone) */
	public static class DuplicateTenantPhoneException extends BusinessException {

		DuplicateTenantPhoneException() {
			super(ErrorCode.EXISTING_TENANT, "phone used by another tenant");
		}

	}

	/** 나가는 날을 당기면 그 뒤로 잡힌 월세 변경이 남는다 → 먼저 취소해야 한다 */
	public static class ChangeAfterEndException extends BusinessException {

		ChangeAfterEndException() {
			super(ErrorCode.LEASE_PERIOD_OVERLAP, "scheduled rent change after new end");
		}

	}

	/** 청구(받을 월세)가 있는 계약은 지울 수 없다 (M2 결정 11). 계약이 끝났으면 종료를 쓴다 */
	public static class LeaseHasChargesException extends BusinessException {

		LeaseHasChargesException() {
			super(ErrorCode.LEASE_HAS_CHARGES, "lease has charges");
		}

		LeaseHasChargesException(Throwable cause) {
			super(ErrorCode.LEASE_HAS_CHARGES, "lease has charges", cause);
		}

	}

	/** 첫 달 청구가 생긴 뒤에는 계약 수정에서 첫 달 금액을 고칠 수 없다. 그 달 청구의 항목에서 고친다 (M3 결정 12) */
	public static class FirstMonthChargedException extends BusinessException {

		FirstMonthChargedException() {
			super(ErrorCode.FIRST_MONTH_CHARGED, "first month already charged");
		}

	}

	/**
	 * 화면이 보던 열린 링크와 지금 열린 링크가 다르다 (M4 결정 2). 새로고침으로 같은 POST가 다시 왔거나 다른 창에서 이미 바꿨다.
	 * 아무것도 바꾸지 않았다.
	 */
	public static class TenantLinkStaleException extends BusinessException {

		TenantLinkStaleException() {
			super(ErrorCode.TENANT_LINK_STALE, "tenant link changed meanwhile");
		}

	}

	/** 계약이 끝나고 링크를 쓸 수 있는 기간이 지나 새 링크를 만들 수 없다 (M4 결정 4) */
	public static class TenantLinkExpiredException extends BusinessException {

		TenantLinkExpiredException() {
			super(ErrorCode.TENANT_LINK_EXPIRED, "tenant link period is over");
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
