package com.rentkeeper.notification.domain;

import java.util.Optional;

import org.springframework.data.repository.Repository;

/** 임대인 알림 설정 저장소 (ADR-018: landlordId로만 찾는다) */
public interface NotificationPreferenceRepository extends Repository<NotificationPreference, Long> {

	NotificationPreference saveAndFlush(NotificationPreference preference);

	Optional<NotificationPreference> findByLandlordId(long landlordId);

}
