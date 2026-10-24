package com.rentkeeper.billing.application;

/**
 * 세입자가 "입금했어요"로 새로 알렸다 (이미 알린 상태에서 다시 누른 것은 아니다). 신고한 트랜잭션 안에서 동기로 알린다: 알림 모듈이
 * 임대인 요약 메일(시간당 1통) 행을 넣는다(M5 결정 4).
 */
public record TenantReportedEvent(long landlordId, long leaseId, long chargeId) {
}
