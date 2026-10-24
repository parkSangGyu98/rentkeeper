package com.rentkeeper.billing.application;

import java.time.LocalDate;

/**
 * 받을 월세를 발행했다 (자동·직접 만들기 모두, ChargeIssuer). 발행한 트랜잭션 안에서 동기로 알린다: 알림 모듈이 같은
 * 트랜잭션에서 Outbox 행을 넣는다(M5 결정 1). billing은 누가 듣는지 모른다.
 *
 * @param today
 *            발행한 날 (서울). 납부일이 이보다 앞이면(누락 복구분) 알림을 만들지 않는다
 */
public record ChargeIssuedEvent(long landlordId, long leaseId, long chargeId, LocalDate dueDate, LocalDate today) {
}
