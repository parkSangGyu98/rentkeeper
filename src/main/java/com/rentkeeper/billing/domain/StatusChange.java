package com.rentkeeper.billing.domain;

/**
 * 청구 상태가 실제로 바뀐 한 번. 서비스가 이것으로 상태 이력(ChargeStatusHistory)을 남긴다. from이 null이면 처음
 * 만듦
 */
public record StatusChange(ChargeStatus from, ChargeStatus to) {

}
