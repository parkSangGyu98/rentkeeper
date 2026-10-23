package com.rentkeeper.config;

import javax.sql.DataSource;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;

/**
 * 배치 스케줄러와 중복 실행 방지 잠금 (ADR-022). 모든 cron에는 zone = "Asia/Seoul"을 명시한다
 * (ADR-002).
 * <p>
 * 모든 {@code @Scheduled} 메서드에는 {@code @SchedulerLock}을 붙인다. 서버가 여러 대여도 같은 작업은 한
 * 곳에서만 돈다. 잠금 시각은 DB 시각(UTC)으로 계산하므로 서버 시계가 어긋나거나 테스트가 앱 Clock을 바꿔도 영향받지 않는다.
 * 업무 날짜(서울)는 작업 안에서 앱 Clock으로 따로 계산한다.
 * <p>
 * order = HIGHEST_PRECEDENCE: 잠금이 {@code @Transactional}보다 바깥에서 감싼다. 잠금을 잡은 뒤
 * 트랜잭션을 시작하고, 커밋이 끝난 뒤 잠금을 푼다. 기본값(LOWEST)은 트랜잭션 advisor와 같아 순서가 정해지지 않는다.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT30M", order = Ordered.HIGHEST_PRECEDENCE)
public class SchedulingConfig {

	@Bean
	LockProvider lockProvider(DataSource dataSource) {
		// 잠금 쓰기는 ShedLock이 자체 새 트랜잭션(REQUIRES_NEW)으로 한다
		return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
				.withJdbcTemplate(new JdbcTemplate(dataSource)).usingDbTime().build());
	}

}
