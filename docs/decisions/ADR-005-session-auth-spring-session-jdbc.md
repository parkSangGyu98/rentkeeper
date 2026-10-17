# ADR-005: 세션 기반 인증과 Spring Session JDBC

- 상태: 승인됨
- 날짜: 2026-10-17

## 맥락
- 화면은 서버 렌더링(ADR-001)이고, 사용자는 브라우저로만 접속한다. 별도 모바일 앱이나 외부 API 소비자는 없다.
- 배포는 단일 VM이지만 재배포·재시작 때 로그인이 풀리면 안 된다. 나중에 서버를 늘릴 여지도 남겨야 한다.
- M1-b에서 "비밀번호 변경 시 그 사용자의 모든 세션 종료", remember-me(30일)가 필요하다.

## 결정
1. **서버 세션 + 쿠키**로 인증한다 (Spring Security 기본 방식). JWT는 쓰지 않는다.
2. 세션은 **Spring Session JDBC**로 PostgreSQL에 저장한다. 앱을 재시작해도 로그인이 유지되고, 서버를 늘려도 세션을 공유한다.
3. 세션 테이블은 **Flyway 마이그레이션(V3)으로 관리**한다. Spring Session 4.1.1 공식 `schema-postgresql.sql`을 내용 변경 없이 옮겼다. `spring.session.jdbc.initialize-schema=never`.
4. 세션의 principal 이름은 이메일이 아니라 **landlord id**로 한다. 이메일은 바뀔 수 있고(M1-b) 카카오 계정에는 없을 수 있다(M1-c). id는 변하지 않으므로 `PRINCIPAL_NAME` 인덱스로 "이 사용자의 모든 세션"을 정확히 찾을 수 있다.
5. 세션 정책: 유휴 타임아웃 **2시간**, 쿠키 **HttpOnly, Secure(로컬 포함), SameSite=Lax**, 이름은 기본값 `SESSION`.
6. 이 정책은 **`spring.session.timeout`과 `SessionCookieConfig`의 `CookieSerializer` 빈 한 곳에만** 둔다. `server.servlet.session.*`는 쓰지 않는다 (아래 "설정 위치" 참고).

## 설정 위치를 프로퍼티에서 코드로 옮긴 이유
처음에는 `server.servlet.session.timeout`과 `server.servlet.session.cookie.*`로 설정했다. 그런데 테스트에서 타임아웃이 2시간이 아니라 30분으로 나왔다.

원인: **Spring Boot 4.1.1은 MockMvc 테스트와 실제 실행에서 세션 설정을 다른 경로로 읽는다.**

| 실행 방식 | Boot가 고르는 설정 | 타임아웃 출처 | 쿠키 속성 출처 |
|---|---|---|---|
| 실제 실행 (내장 Tomcat) | `EmbeddedWebServerConfiguration` | `spring.session.timeout` → 없으면 `server.servlet.session.timeout` | `server.servlet.session.cookie.*` |
| `@SpringBootTest` MOCK 환경 (내장 서버 없음) | `WarDeploymentConfiguration` (WAR 배포로 판정) | `spring.session.timeout`만 | 서블릿 컨텍스트의 기본 쿠키 설정 (`server.servlet.session.cookie.*` 무시) |

그대로 두면 운영은 맞게 동작하지만, MockMvc 테스트는 운영과 다른 쿠키·타임아웃을 보게 되어 검증이 의미가 없어진다. 그래서:
- 타임아웃은 두 경로 모두 먼저 읽는 `spring.session.timeout`에 둔다.
- 쿠키 속성은 `CookieSerializer` 빈으로 직접 만든다. 이 빈이 있으면 Boot는 두 경로 모두에서 자기 쿠키 설정을 만들지 않는다.
- 결정 내용(2시간, HttpOnly, Secure, Lax)은 바뀌지 않았고 **설정 위치만 바뀌었다.**
- 실제 Tomcat 경로는 `SessionCookieRealServerTests` 1개(RANDOM_PORT)로 확인하고, 나머지 테스트는 MockMvc로 한다. MockMvc 환경이 같은 쿠키 설정을 쓰는지는 `SpringSessionJdbcTests`에서 확인한다.

## 검토한 대안
| 대안 | 채택하지 않은 이유 |
|---|---|
| JWT(무상태 토큰) | 로그아웃·강제 종료(비밀번호 변경 시 전 세션 종료)가 어렵다. 토큰 저장 위치(쿠키/스토리지)와 갱신 전략이 추가로 필요하다. 브라우저 전용 SSR 앱에서 얻는 이점이 없다 |
| 서버 메모리 세션(Tomcat 기본) | 재배포·재시작마다 모두 로그아웃된다. 서버 증설 시 세션 공유 불가 |
| Redis 세션 | 성능은 좋지만 MVP에서 캐시를 쓰지 않기로 했다(PRD 3.1). 운영할 구성 요소가 하나 늘어난다 |
| `initialize-schema=always` | 시작할 때마다 DDL을 실행하고, 실패해도 `continue-on-error=true`라 조용히 넘어간다. 스키마 출처가 Flyway와 둘로 나뉜다 |

## 결과
- 매 요청마다 세션 조회 쿼리가 생긴다. MVP 규모(임대인 1,000명)에서는 문제없고, 느려지면 측정 후 Redis를 검토한다.
- 세션 안의 로그인 정보(principal)는 **JDK 직렬화**로 저장된다. 클래스 구조를 바꿔 배포하면 기존 세션을 읽지 못해 사용자들이 로그아웃될 수 있다. 그래서 principal에는 id·이메일·이름 같은 작은 값만 담고 `serialVersionUID`를 고정한다. 문제가 되면 JSON 직렬화로 바꾸는 것을 검토한다.
- 만료 세션은 Spring Session이 1분마다 정리한다(`cleanup-cron` 기본값).
