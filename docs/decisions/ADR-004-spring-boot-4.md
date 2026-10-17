# ADR-004: Spring Boot 3.x 대신 4.1.x로 시작한다

- 상태: 승인됨
- 날짜: 2026-10-17

## 맥락
- PRD v0.1은 "Spring Boot 3.x"를 명시했지만, 개발 시작 시점(2026-10) 기준 지원 일정은 다음과 같다 (출처: spring.io 프로젝트 페이지, endoflife.date).

| 버전 | 최신 패치 | OSS 지원 종료 | 상용 지원 종료 |
|---|---|---|---|
| 3.5.x | 3.5.16 | **2026-06-30 (이미 종료)** | 2032-06-30 |
| 4.0.x | 4.0.8 | 2026-12-31 | 2027-12-31 |
| 4.1.x | **4.1.1** (2026-08-20) | 2027-07-31 | 2028-07-31 |

- 출시 목표는 수개월 뒤이고, 그 이후에도 운영해야 한다. 지원이 끝난 버전으로 새 프로젝트를 시작하면 보안 패치를 받을 수 없다.

## 결정
- **Spring Boot 4.1.1**을 사용한다 (2026-10-17 기준 최신 GA. 4.2.0-M2는 마일스톤이라 제외).
- 라이브러리 버전은 Boot BOM(`platform(SpringBootPlugin.BOM_COORDINATES)`)에 맡기고 `build.gradle.kts`에 개별 버전을 적지 않는다.
- Gradle은 9.8.1로 고정한다. **안정 릴리스 후 2주 경과 규칙의 예외: 9.8.1은 출시 10일차(2026-10-07)지만 High 등급 보안 취약점 3건(데몬 통신 역직렬화, SSL 실패 저장소 처리)을 수정한 패치 릴리스이므로 보안 패치를 우선했다.**

## 4.0이 아니라 4.1인 이유
- 4.0의 OSS 지원은 2026-12-31에 끝난다(약 2.5개월 남음). 4.1은 2027-07까지 지원되고, 이후 4.2로의 마이너 업그레이드는 부담이 작다.

## Boot 4에서 바뀐 점 (이 프로젝트에 영향 있는 것)
| 영역 | 변경 | 영향 |
|---|---|---|
| 기반 | Spring Framework 7, Jakarta EE 11, Servlet 6.1(Tomcat 11) | 최소 Java 17 (우리는 21) |
| 모듈화 | 자동 구성이 기술별 모듈(`spring-boot-<기술>`)로 분리 | Flyway는 `spring-boot-starter-flyway`가 있어야 자동 구성됨 (라이브러리만 넣으면 동작 안 함) |
| 스타터 이름 | `starter-web` → `starter-webmvc`, `starter-oauth2-client` → `starter-security-oauth2-client` 등 | 옛 이름은 deprecated. M0부터 새 이름 사용, M1 카카오 OAuth2에서 새 이름 사용 |
| 테스트 | 기술별 test starter(`starter-webmvc-test`, `starter-data-jpa-test`, `starter-security-test`), `@SpringBootTest`가 MockMvc를 자동 제공하지 않음(`@AutoConfigureMockMvc` 필요), `@MockBean` 삭제 → `@MockitoBean` | M1 웹·보안 테스트부터 반영 |
| Jackson | Jackson 3 (`tools.jackson` 패키지, `JsonMapper` 빈) | JSON 커스터마이징 시 새 API 사용 |
| Hibernate | 7.x (4.1.1 BOM: 7.4.5.Final) | **QueryDSL 호환성은 M2에서 검토** |
| Testcontainers | 2.x (4.1.1 BOM: 2.0.5), 아티팩트명 `testcontainers-postgresql` 등으로 변경 | M0 테스트에 반영 |
| Null 안정성 | JSpecify 어노테이션 도입 | 정적 분석 도입 시 참고 |
| Actuator | liveness/readiness probe가 health에 기본 노출 | 배포(M6) 헬스체크에 활용 가능 |

## 결과
- 앞으로 쓸 서드파티(QueryDSL, ShedLock, thymeleaf-extras-springsecurity 등)가 Boot 4/Hibernate 7/Security 7과 호환되는지 각 마일스톤 계획 단계에서 확인한다 (M1: Security 7 + Thymeleaf extras, M2: QueryDSL, M3: ShedLock).
- 인터넷 자료 상당수가 Boot 3 기준이므로 코드 예제를 옮길 때 위 표의 변경점을 확인한다.
- Dependabot(쿨다운 14일)으로 패치 버전을 따라간다.
