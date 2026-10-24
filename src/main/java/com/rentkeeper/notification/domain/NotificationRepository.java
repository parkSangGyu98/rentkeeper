package com.rentkeeper.notification.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.repository.Repository;

/**
 * 알림 저장소. 행은 NotificationOutbox가 SQL로 넣는다(ON CONFLICT DO NOTHING). 화면에서 쓰는 조회는
 * landlordId를 받는다 (ADR-018).
 * <p>
 * {@code ForWorker} 메서드만 예외로 임대인을 가로질러 ID로 찾는다: 발송 워커는 모든 임대인의 알림을 차례로 보내고, 운영
 * 알림에는 임대인이 없다. 화면·컨트롤러에서는 쓰지 않는다(OwnedRepositoryConventionTest).
 */
public interface NotificationRepository extends Repository<Notification, Long> {

	void flush();

	/** 발송 워커 전용 */
	Optional<Notification> findForWorkerById(long id);

	/** 발송 워커 전용 (가져갈 행을 잠근 같은 트랜잭션 안에서) */
	List<Notification> findForWorkerByIdIn(Collection<Long> ids);

}
