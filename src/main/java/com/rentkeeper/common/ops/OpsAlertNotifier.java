package com.rentkeeper.common.ops;

/**
 * 운영자 알림 (M1-c 결정). 지금은 ERROR 로그 구현(LoggingOpsAlertNotifier)만 있다. 운영
 * 메일(OPS_ALERT_EMAIL, 하루 한 통으로 묶음)은 운영 메일 발송이 생기는 M5에서 이 인터페이스의 구현으로 추가한다.
 * <p>
 * 알림 내용에는 개인정보(회원번호·이메일·이름)를 넣지 않는다. 건수와 오류 종류만.
 */
public interface OpsAlertNotifier {

	/**
	 * @param code
	 *            로그 검색·감시에 쓰는 정해진 문구 (예: KAKAO_UNLINK_ALERT)
	 * @param summary
	 *            건수와 종류만 담은 한 줄 (예: "config=1 transient=0")
	 */
	void alert(String code, String summary);

}
