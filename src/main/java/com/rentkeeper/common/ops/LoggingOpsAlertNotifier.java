package com.rentkeeper.common.ops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 운영자 알림을 ERROR 로그 한 줄로 남긴다. 로거 이름 {@value #LOGGER_NAME}과 정해진 문구로 찾는다 (M6에서 로그
 * 감시와 연결).
 */
@Component
public class LoggingOpsAlertNotifier implements OpsAlertNotifier {

	public static final String LOGGER_NAME = "rentkeeper.ops-alert";

	private static final Logger log = LoggerFactory.getLogger(LOGGER_NAME);

	@Override
	public void alert(String code, String summary) {
		log.error("{} {}", code, summary);
	}

}
