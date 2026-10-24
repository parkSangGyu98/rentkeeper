package com.rentkeeper.notification.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.rentkeeper.notification.domain.NotificationPreference;
import com.rentkeeper.notification.domain.NotificationPreferenceRepository;
import com.rentkeeper.notification.domain.NotificationType;

/** 임대인 알림 설정 읽기·저장 (M5 결정 13). 행이 없으면 모두 켬 */
@Service
@Transactional(readOnly = true)
public class NotificationPreferences {

	private final NotificationPreferenceRepository preferences;

	NotificationPreferences(NotificationPreferenceRepository preferences) {
		this.preferences = preferences;
	}

	/** 설정 화면 값 */
	public record Settings(boolean tenantReport, boolean overdueDigest, boolean leaseEndDigest, boolean tenantSms) {
	}

	public Settings settings(long landlordId) {
		NotificationPreference p = find(landlordId);
		return new Settings(p.isTenantReport(), p.isOverdueDigest(), p.isLeaseEndDigest(), p.isTenantSms());
	}

	public boolean allows(long landlordId, NotificationType type) {
		return find(landlordId).allows(type);
	}

	@Transactional
	public void save(long landlordId, Settings settings) {
		NotificationPreference p = preferences.findByLandlordId(landlordId)
				.orElseGet(() -> NotificationPreference.defaults(landlordId));
		p.change(settings.tenantReport(), settings.overdueDigest(), settings.leaseEndDigest(), settings.tenantSms());
		preferences.saveAndFlush(p);
	}

	private NotificationPreference find(long landlordId) {
		return preferences.findByLandlordId(landlordId).orElseGet(() -> NotificationPreference.defaults(landlordId));
	}

}
