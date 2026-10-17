# ADR-020: ArchUnit으로 모듈 의존 방향과 시간 규칙 강제

- 상태: 승인됨
- 날짜: 2026-10-17

## 맥락
- PRD 10장: 도메인 단위 모듈러 모놀리스. 모듈 간 참조는 서비스 인터페이스나 이벤트로만 한다. `controller → service → repository` 단방향.
- ADR-002: `LocalDate.now()`처럼 인자 없는 `now()`는 금지다(Clock 주입). 리뷰만으로는 막기 어렵다.
- M2에서 건물(property)·계약(lease) 모듈이 생겨 모듈 사이 의존이 늘어난다. 규칙을 테스트로 강제할 시점이다.

## 결정
1. `com.tngtech.archunit:archunit-junit5:1.4.2`(테스트 전용). Boot BOM이 관리하지 않아 버전을 직접 적는다.
2. `ArchitectureTests`가 아래 규칙을 검사한다. 하나라도 어기면 빌드가 실패한다.
   - **의존 방향**: `lease → property → landlord ← auth → consent → site`. 모든 모듈은 `common`을 쓸 수 있다.
     - `common`은 어떤 모듈에도 기대지 않는다.
     - `landlord`는 `common`만 쓴다.
     - `property`는 `lease`를 쓰지 않는다. 호실 상태처럼 계약 정보가 필요하면 lease 쪽에서 계산하거나 결과를 넘긴다.
     - `config`는 조립만 한다. 다른 모듈은 `config`를 쓰지 않는다.
   - **property·lease는 auth에서 로그인 정보(`auth.security`)만 쓴다**: 화면의 `@AuthenticationPrincipal LandlordPrincipal`.
   - **다른 모듈의 엔티티(`@Entity`)와 리포지토리를 쓰지 않는다**: 서비스(`application`)와 값 타입·예외만 쓴다.
   - **컨트롤러는 리포지토리를 쓰지 않는다.**
   - **인자 없는 `now()` 금지**: `LocalDate`·`LocalDateTime`·`LocalTime`·`Instant`·`ZonedDateTime`·`OffsetDateTime`·`YearMonth`·`Year`.
3. 테스트 코드는 검사하지 않는다(테스트는 고정 시각과 픽스처를 자유롭게 쓴다).
4. property·lease 규칙에는 패키지가 생기기 전까지만 `allowEmptyShould(true)`를 둔다. 두 모듈이 생기는 M2 커밋 4에서 뺀다. 빼지 않으면 패키지 이름이 바뀌어도 규칙이 조용히 통과해 버린다.

## 도입 때 확인한 것
- 위반 코드(인자 없는 `LocalDate.now()`, common에서 landlord 리포지토리 사용)를 잠시 넣어 세 규칙이 실패하는지 확인한 뒤 지웠다.
- 기존 코드의 위반은 없었다.
- `auth.kakao.KakaoProfile`이 엔티티 상수 `Landlord.NAME_MAX_LENGTH`를 쓰고 있었다. 컴파일 때 상수 값이 그대로 박혀서 ArchUnit에는 보이지 않지만, 규칙 취지에 맞게 `LandlordAccount.NAME_MAX_LENGTH`로 옮겼다.

## 한계
- 바이트코드로 검사하므로 컴파일 상수(위 사례)와 SQL 문자열 안의 테이블 이름(예: 파기 배치의 `delete from landlord`)은 잡지 못한다.
- 서비스 계층의 `@Transactional` 위치 같은 규칙은 넣지 않았다. 필요해지면 추가한다.

## 검토한 대안
| 대안 | 채택하지 않은 이유 |
|---|---|
| Spring Modulith | 모듈 구조 검증 외에 이벤트·문서화까지 들어온다. 지금은 의존 방향 검사만 필요하다 |
| Gradle 멀티 모듈로 분리 | 의존을 컴파일 단계에서 막을 수 있지만, 1인 개발 MVP에 빌드 구성이 과하게 복잡해진다 |
| 리뷰로만 관리 | 빠뜨리기 쉽다. ADR-002에서 이미 한계를 적었다 |
