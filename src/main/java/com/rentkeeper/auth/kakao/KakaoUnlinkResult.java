package com.rentkeeper.auth.kakao;

/**
 * 카카오 연결 끊기 호출 결과 (ADR-015). 실패 종류는 kakao_unlink_pending.last_failure_type 값과
 * 같다 (V11).
 */
public enum KakaoUnlinkResult {

	/** 2xx, 또는 400 + 카카오 에러 코드 -101(앱과 연결되지 않은 사용자: 끊을 연결이 없음) */
	SUCCESS,

	/** 401/403 또는 어드민 키 없음: 어드민 키·허용 IP 설정 오류. 재시도로 풀리지 않아 첫 실패에 알린다 */
	CONFIG,

	/** 5xx, 429, 시간 초과, 연결 실패: 대개 다시 시도하면 풀린다. 연속 3회부터 알린다 */
	TRANSIENT,

	/** 그 밖의 4xx (400이라도 카카오 코드가 -101이 아니거나 없으면 여기). 재시도로 풀릴지 알 수 없어 첫 실패에 알린다 */
	REJECTED;

	public boolean isFailure() {
		return this != SUCCESS;
	}

}
