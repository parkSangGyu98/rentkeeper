# ADR-008: 보안 헤더·CSP·기본 거부 접근 정책

- 상태: 승인됨
- 날짜: 2026-10-17

## 맥락
- PRD 8.1 보안 체크리스트: CSRF, 보안 헤더(CSP, X-Content-Type-Options, X-Frame-Options, Referrer-Policy, HSTS), XSS 방지(`th:utext` 금지), 소유권 검증.
- 1인 개발이라 리뷰어가 없다. 규칙은 사람의 주의가 아니라 설정과 테스트로 강제해야 한다.
- 화면은 Thymeleaf + Tailwind(정적 CSS 파일)이고, HTMX는 나중에 필요한 곳에서 도입한다.

## 결정
1. **접근 제어는 기본 거부**다.
   - 허용 목록(`/`, `/login`, `/signup`, `/terms`, `/privacy`, `/error`, `/css/**`, `/js/**`, `/images/**`, `/favicon.ico`, `/actuator/health`)은 모두에게 연다.
   - `/app/**`는 `ROLE_LANDLORD`만 접근한다.
   - 그 밖의 경로는 `denyAll()`이다. 비로그인 사용자는 로그인 화면으로 이동하고, 로그인한 사용자는 403을 받는다.
   - 새 화면을 만들고 등록을 잊으면 "열리지 않고 막히는" 쪽으로 실패한다.
2. **CSRF**는 Spring Security 기본값(활성)을 그대로 쓴다. Thymeleaf `th:action` 폼에 토큰이 자동으로 들어간다. 로그아웃은 POST만 받는다.
3. **CSP**: `default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; font-src 'self'; form-action 'self'; frame-ancestors 'none'; base-uri 'self'; object-src 'none'`
   - 외부 CDN·웹폰트·인라인 스크립트/스타일을 쓰지 않는다. `nonce`나 `'unsafe-inline'`이 필요 없다.
4. 그 밖의 헤더: `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: strict-origin-when-cross-origin`(세입자 링크 페이지는 M4에서 `no-referrer`). HSTS는 Spring Security가 HTTPS 요청에만 붙인다 → 운영(M6)에서 프록시 뒤 HTTPS 판정 설정이 필요하다.
5. **템플릿 규칙은 테스트로 강제**한다 (`TemplateGuardTest`). `th:utext`, 인라인 `<script>`, `<style>`, `style=""`, `on*=""` 이벤트 속성, `javascript:` 링크가 있으면 빌드가 실패한다.
6. 로그인 실패 안내는 하나로 통일한다("이메일 또는 비밀번호가 맞지 않아요"). 없는 계정인지 비밀번호가 틀렸는지 구분하지 않는다.
7. 템플릿의 로그인 상태 표시는 `thymeleaf-extras-springsecurity6`(`sec:authorize`, `sec:authentication`)로 한다.
   - 이름에 6이 붙어 있지만 Boot 4.1.1 BOM이 Security 7.1.1과 함께 관리하는 버전(3.1.5.RELEASE)이다. `springsecurity7` 아티팩트는 없다.
   - Thymeleaf 쪽 공식 문서에서 "Security 7 지원" 문구는 확인하지 못했다. 대신 `SecurityTests.appHomeShowsNameAndLogoutButton`으로 Security 7에서 동작하는 것을 확인했다.
   - 문제가 생기면 `@ControllerAdvice`로 로그인 정보를 모델에 넣는 방식으로 바꾼다.

## HTMX를 도입할 때 지킬 것 (CSP와 충돌 방지)
- htmx는 기본 설정에서 로딩 표시용 `<style>`을 페이지에 주입한다 → `<meta name="htmx-config" content='{"includeIndicatorStyles":false}'>`로 끄고 그 스타일은 Tailwind에 둔다.
- `hx-on:*`, `js:` 표현식은 `eval`이 필요해 `'unsafe-eval'`을 요구한다 → 쓰지 않는다 (`allowEval:false`).
- CDN이 아니라 `/js/`에 자체 호스팅한다.

## 검토한 대안
| 대안 | 채택하지 않은 이유 |
|---|---|
| 그 밖의 경로를 `authenticated()`로 | 로그인한 사용자에게 없는 경로가 404로 보이는 장점이 있지만, 새 경로를 만들 때 권한 설정을 잊으면 로그인한 누구에게나 열린다 |
| CSP `'unsafe-inline'` 허용 | XSS가 생겼을 때 CSP가 막아 주지 못한다 |
| CSP를 Report-Only로 시작 | 인라인 코드가 하나도 없는 상태에서 시작하므로 처음부터 강제해도 깨질 것이 없다 |

## 결과
- 비로그인 상태로 없는 주소에 들어가면 404가 아니라 로그인 화면으로 간다. 로그인 상태에서는 403 안내 화면이 나온다. 404 안내 화면은 허용 경로 안의 없는 파일(예: `/css/no-such.css`)에서 보인다.
- 인터넷 예제를 그대로 붙여 넣으면(인라인 스크립트 등) `TemplateGuardTest`에서 빌드가 실패한다. 의도한 동작이다.
- 카카오 로그인(M1-c)은 GET 링크(`/oauth2/authorization/kakao`)로 시작해 `form-action 'self'`와 충돌하지 않게 한다. M1-c에서 실제 동작을 확인한다.
