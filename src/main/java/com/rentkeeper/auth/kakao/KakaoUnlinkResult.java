package com.rentkeeper.auth.kakao;

/**
 * 카카오 연결 끊기 호출 결과 (ADR-015). 실패 종류는 kakao_unlink_pending.last_failure_type 값과
 * 같다 (V11).
 */
public enum KakaoUnlinkResult {

	/** 2xx */
	SUCCESS,

	/** 401/403 또는 어드민 키 없음: 어드민 키·허용 IP 설정 오류. 재시도로 풀리지 않아 첫 실패에 알린다 */
	CONFIG,

	/** 5xx, 429, 시간 초과, 연결 실패: 대개 다시 시도하면 풀린다. 연속 3회부터 알린다 */
	TRANSIENT,

	/** 그 밖의 4xx. 이미 끊긴 회원일 때의 응답이 여기 들어갈 것으로 보지만 확인 전이라 실패로 기록하고 첫 실패에 알린다 */
	REJECTED;

	public boolean isFailure() {
		return this != SUCCESS;
	}

}
