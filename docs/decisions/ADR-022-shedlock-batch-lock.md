# ADR-022: 배치 중복 실행 방지 (ShedLock)

- 상태: 승인됨 (M3 커밋 1)
- 날짜: 2026-10-18

## 맥락
- M3 청구 생성 배치는 돈을 다룬다. 서버가 두 대 이상이거나 재배포 중 두 프로세스가 겹치면 같은 작업이 동시에 돈다. 청구는 UNIQUE(lease_id, billing_month)로 멱등이지만, 겹쳐 돌면 실패 경고와 행 경합이 생기고 원인을 찾기 어렵다.
- M1·M2의 정리 작업 4개(탈퇴 파기, remember-me 정리, 계정 토큰 정리, 레이트 리미트 정리)는 다시 돌아도 결과가 같아 잠금 없이 시작했다. 도입은 M3로 미뤘다.

## 결정
- **ShedLock 7.10.1** + `shedlock-provider-jdbc-template`, 잠금 테이블은 `shedlock`(V15, 공식 PostgreSQL DDL 그대로).
- 확인한 사실 (2026-10-18, 공식 자료와 소스):
  - Maven Central `shedlock-spring` 메타데이터: latest·release = 7.10.1 (lastUpdated 2026-09-08 UTC).
  - README 호환 표: 7.x.x는 최소 Java 17, "Spring 7.0, 6.2, Spring Boot 4.x, 3.5, 3.4"에서 테스트했다.
  - `shedlock-parent-7.10.1.pom`: `spring.version` 7.0.9, `spring-boot-current.version` 4.1.1. 태그 `shedlock-parent-7.10.1`의 `spring/test/shedlock-springboot-test/pom.xml`이 `spring-boot-dependencies:${spring-boot-current.version}`(=4.1.1) BOM을 가져와 Boot 통합 테스트를 돌린다.
  - 이 프로젝트(Boot 4.1.1 BOM)가 실제로 고른 `spring-context`도 7.0.9다(`dependencyInsight`). ShedLock이 빌드된 Spring 버전과 같다.
  - `JdbcTemplateStorageAccessor`는 잠금을 쓸 때 자체 `TransactionTemplate`(REQUIRES_NEW)을 쓴다. 트랜잭션 관리자를 주지 않으면 DataSource로 `DataSourceTransactionManager`를 만든다.
  - `@EnableSchedulerLock` 기본값: `interceptMode = PROXY_METHOD`(메서드 프록시), `order = LOWEST_PRECEDENCE`.
- **시각**: `usingDbTime()`. 잠금 시각은 DB 서버의 UTC 현재 시각으로 계산한다. 서버 시계가 어긋나거나 테스트가 앱 Clock을 바꿔도 잠금은 영향받지 않는다. 업무 날짜(청구월·납부일, 서울)는 작업 안에서 앱 Clock으로 따로 계산한다(ADR-002). 두 시각을 섞지 않는다.
- **advisor 순서**: `order = HIGHEST_PRECEDENCE`. 잠금이 `@Transactional`보다 바깥에서 감싼다. 잠금을 잡은 뒤 트랜잭션을 시작하고, 커밋한 뒤 잠금을 푼다. 기본값은 트랜잭션 advisor와 같은 LOWEST라 순서가 정해지지 않는다.
- **규칙**: 모든 `@Scheduled` 메서드는 `@SchedulerLock`을 붙이고 public이다(`ArchitectureTests.scheduledMethodsAreLocked`). 기본 `lockAtMostFor`는 30분.
- **작업별 값** (lockAtLeastFor는 모두 1분: 다른 서버가 같은 차례에 몇 초 늦게 시작해도 건너뛰게)

| 작업 (잠금 이름) | 일정 | 실행 시간 근거 | lockAtMostFor |
|---|---|---|---|
| withdrawn-account-purge | 매일 03:30 | 계정 파기는 계정당 트랜잭션 하나(밀리초). 가장 긴 것은 카카오 연결 끊기 재시도로 1건 최대 8초(연결 3초 + 응답 5초 타임아웃). 대기 행은 평소 0~몇 건 | 2시간 (대기 900건까지) |
| account-token-cleanup | 매일 04:00 | 삭제 쿼리 한 번 (만료 인덱스) | 30분 |
| remember-me-cleanup | 매일 04:10 | 삭제 쿼리 한 번 | 30분 |
| rate-limit-cleanup | 10분마다 (fixedDelay) | 삭제 쿼리 한 번 (expires_at 인덱스) | 9분 |

  - lockAtMostFor는 "잠금을 잡은 서버가 죽었을 때 언제 풀지"다. 작업이 이보다 오래 걸리면 잠금이 먼저 풀려, 그 사이 같은 작업을 시작한 다른 서버가 겹쳐 돌 수 있다. 그래서 위 값은 예상 실행 시간보다 넉넉히 길게 둔다.
  - 하루 한 번 작업은 다른 서버도 같은 시각(cron)에 시작하므로, 잠금이 일찍 풀려도 다음 시작은 다음 날이다. 겹치려면 서버 시계가 lockAtMostFor 이상 어긋나야 한다. 그래도 겹쳐 돌면 탈퇴 파기는 계정마다 `for update`로 다시 확인하고, 정리 작업은 같은 행을 지울 뿐이라 결과가 같다.
  - 레이트 리미트 정리는 간격(10분)보다 짧은 9분이다. 잠금을 잡은 서버가 죽어도 다음 차례에는 풀려 있다.
  - 청구 생성(charge-generation, 매일 03:00)과 납부일 지남(charge-overdue, 매일 00:10)은 lockAtMostFor 1시간이다. 계약 2만 건 측정에서 가장 무거운 날 2분 39초, 납부일 지남 2만 건 48초였다(ADR-024).

## 결과
- 서버를 늘려도 배치가 한 곳에서만 돈다. 잠금 행은 `shedlock` 테이블에 작업 이름마다 하나씩 남는다(locked_by = 호스트명).
- 테스트에서 작업을 1분 안에 두 번 부르면 두 번째는 건너뛴다. 공용 정리(`TestData.reset`)가 잠금을 모두 푼다(`lock_until = locked_at`).
- **`shedlock` 행은 지우지 않는다.** ShedLock은 한 번 만든 잠금 행을 앱 안에 기억해 두고 그다음부터 UPDATE만 한다. 앱이 도는 중에 행을 지우면 UPDATE가 0행이 되어 잠금을 못 잡은 것으로 보고 그 작업을 계속 건너뛴다(앱을 다시 시작해야 풀린다). 잠금을 강제로 풀려면 `update shedlock set lock_until = locked_at where name = '...'`를 쓴다. 테스트를 만들다 실제로 겪었다.
- 잠금은 "동시에 한 번"만 보장한다. 정확히 한 번 실행은 보장하지 않는다(서버가 죽으면 그 차례를 건너뛸 수 있다). 청구 생성은 누락 복구(최근 12개월)로 이것을 메운다(M3 결정 21).
