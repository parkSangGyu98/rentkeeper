package com.rentkeeper.lease.application;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.rentkeeper.lease.domain.Lease;
import com.rentkeeper.lease.domain.LeaseRepository;
import com.rentkeeper.lease.domain.Tenant;
import com.rentkeeper.lease.domain.TenantRepository;
import com.rentkeeper.property.application.PropertyService;

/**
 * 세입자 알림을 받을 사람 (M5 결정 9·8). 알림 모듈이 계획할 때(수신 거부·알림 멈춤 확인)와 보낼 때(번호 복호화) 쓴다. 번호는
 * 보낼 때만 꺼내고 저장하지 않는다.
 */
@Service
@Transactional(readOnly = true)
public class TenantNoticeRecipients {

	private final LeaseRepository leases;

	private final TenantRepository tenants;

	private final PropertyService propertyService;

	TenantNoticeRecipients(LeaseRepository leases, TenantRepository tenants, PropertyService propertyService) {
		this.leases = leases;
		this.tenants = tenants;
		this.propertyService = propertyService;
	}

	/**
	 * @param unitName
	 *            "건물 호실" (본문 머리말)
	 * @param phone
	 *            휴대폰 번호 숫자 (보낼 때만 쓴다)
	 * @param optedOut
	 *            세입자가 안내 문자를 그만 받기로 함 (TEN-6)
	 * @param paused
	 *            임대인이 [링크 닫기]로 이 계약의 알림을 멈춤
	 */
	public record NoticeRecipient(long tenantId, String unitName, String phone, boolean optedOut, boolean paused) {

		@Override
		public String toString() {
			return "NoticeRecipient[tenantId=" + tenantId + ", phone=***, optedOut=" + optedOut + ", paused=" + paused
					+ "]";
		}

	}

	/** 그 계약의 세입자 (남의 계약이면 빈 값) */
	public Optional<NoticeRecipient> recipient(long landlordId, long leaseId) {
		Optional<Lease> lease = leases.findByIdAndLandlordId(leaseId, landlordId);
		if (lease.isEmpty()) {
			return Optional.empty();
		}
		Lease l = lease.get();
		return tenants.findByIdAndLandlordId(l.getTenantId(), landlordId)
				.map(t -> recipient(t, l, propertyService.unit(landlordId, l.getUnitId()).displayName()));
	}

	private static NoticeRecipient recipient(Tenant tenant, Lease lease, String unitName) {
		return new NoticeRecipient(tenant.getId(), unitName, tenant.getPhone().digits(), tenant.notificationsOptedOut(),
				lease.tenantNotificationsPaused());
	}

}
