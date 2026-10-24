package com.rentkeeper.notification.application;

import java.util.Optional;

import org.springframework.stereotype.Component;

import com.rentkeeper.landlord.application.LandlordAccount;
import com.rentkeeper.landlord.application.LandlordService;
import com.rentkeeper.landlord.domain.LandlordStatus;
import com.rentkeeper.notification.domain.NotificationType;
import com.rentkeeper.notification.domain.SkipReason;

/**
 * 임대인 요약 메일 행 넣기 (M5 결정 4·13). 만들 때 알림 설정과 이메일 인증을 보고, 해당하면 "보내지 않음"으로 남긴다. 인증한
 * 뒤에도 지난 알림은 보내지 않는다(끝난 상태는 다시 바뀌지 않는다). 보낼 때 한 번 더
 * 본다(LandlordNotificationComposer).
 */
@Component
class LandlordNotices {

	private final NotificationPreferences preferences;

	private final LandlordService landlordService;

	private final NotificationOutbox outbox;

	LandlordNotices(NotificationPreferences preferences, LandlordService landlordService, NotificationOutbox outbox) {
		this.preferences = preferences;
		this.landlordService = landlordService;
		this.outbox = outbox;
	}

	boolean enqueue(NotificationType type, long landlordId, String dedupeKey) {
		NotificationRequest request = NotificationRequest.landlord(type, landlordId, dedupeKey);
		Optional<SkipReason> skip = skipReason(type, landlordId);
		return skip.isPresent() ? outbox.enqueueSkipped(request, skip.get()) : outbox.enqueue(request);
	}

	/** 설정이 꺼졌거나 보낼 주소(사용 중 계정의 인증한 이메일)가 없으면 이유 */
	Optional<SkipReason> skipReason(NotificationType type, long landlordId) {
		if (!preferences.allows(landlordId, type)) {
			return Optional.of(SkipReason.LANDLORD_OFF);
		}
		return verifiedEmail(landlordId).isPresent() ? Optional.empty() : Optional.of(SkipReason.EMAIL_UNVERIFIED);
	}

	/** 사용 중 계정의 인증한 이메일 (M1: 알림은 인증된 주소로만) */
	Optional<String> verifiedEmail(long landlordId) {
		return landlordService.findAccount(landlordId)
				.filter(a -> a.status() == LandlordStatus.ACTIVE && a.emailVerified() && a.email() != null)
				.map(LandlordAccount::email);
	}

}
