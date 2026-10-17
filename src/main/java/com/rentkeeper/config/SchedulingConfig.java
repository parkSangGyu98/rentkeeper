package com.rentkeeper.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 정리 작업(만료 토큰, remember-me, 레이트 리미트 카운터)용 스케줄러. 모든 cron에는 zone = "Asia/Seoul"을
 * 명시한다 (ADR-002).
 * <p>
 * ShedLock(중복 실행 방지)은 청구 배치가 생기는 M3에서 도입한다. 지금 작업들은 삭제만 해서 두 번 돌아도 결과가 같다.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class SchedulingConfig {

}
