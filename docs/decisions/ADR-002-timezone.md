# ADR-002: 시간대와 시간 처리 방식

- 상태: 승인됨
- 날짜: 2026-10-17

## 맥락
- 서비스 대상은 한국 사용자뿐이고, 업무 규칙이 모두 KST 기준이다 (오전 3시 청구 배치, 납부일 경과 판정, 알림 허용 시간 09~20시).
- 청구월·납부일 같은 "달력 날짜"와 발송 시각·결제 시각 같은 "순간(instant)"은 성격이 다르다.
- 납부일 말일 보정, 배치 멱등성 등 날짜에 민감한 로직을 테스트로 검증해야 한다.

## 결정
1. **JVM 기본 시간대는 `Asia/Seoul`로 고정**한다. `bootRun`·`test` 태스크와 이후 배포 이미지에 `-Duser.timezone=Asia/Seoul`을 준다.
2. **순간은 DB에 `timestamptz`로 저장**하고, Hibernate에 `hibernate.jdbc.time_zone=UTC`를 설정한다. 자바 타입은 `Instant`(또는 `OffsetDateTime`)를 쓴다.
3. **달력 날짜는 `date` / `LocalDate`로 저장**한다. 의미는 항상 KST 기준 날짜다. 예: `billing_month`(매월 1일로 정규화), `due_date`, `start_date`.
4. **현재 시각은 주입된 `Clock`에서만 얻는다.** `Clock.system(ZoneId.of("Asia/Seoul"))` 빈을 등록하고, `LocalDate.now()`처럼 인자 없는 `now()`는 쓰지 않는다. `LocalDate.now(clock)` 형태만 허용한다.
5. 스케줄러 cron에는 `zone = "Asia/Seoul"`을 명시한다 (JVM 기본값에 기대지 않는다).

## 검토한 대안
| 대안 | 채택하지 않은 이유 |
|---|---|
| JVM·DB 모두 UTC, 화면에서만 KST 변환 | 업무 규칙이 전부 KST라 변환 지점이 많아지고 "오늘" 판정 버그가 생기기 쉽다 |
| `timestamp`(시간대 없음)에 KST 그대로 저장 | 서버·드라이버 시간대 설정에 따라 값이 어긋날 수 있고, 외부 시스템(로그, Sentry)과 비교가 어렵다 |
| `Clock` 없이 `now()` 직접 호출 | 테스트에서 시간을 고정할 수 없어 말일/2월/배치 경계 테스트가 불안정해진다 |

## 결과
- 테스트는 `Clock.fixed(...)`로 원하는 날짜를 만들어 경계 조건(2월 말일, 자정 직전·직후)을 재현할 수 있다.
- JVM 시간대 고정 + `Clock` 명시 zone이 이중 안전장치가 된다. 어느 한쪽이 빠져도 KST 판정이 유지된다.
- 주의: `LocalDate.now()` 금지는 리뷰로만 막기 어렵다. M2에서 ArchUnit 규칙 추가를 검토한다.
