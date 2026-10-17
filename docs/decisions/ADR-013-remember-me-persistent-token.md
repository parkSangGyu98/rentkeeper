# ADR-013: 로그인 유지(remember-me 30일) — PersistentToken + 해시 저장

- 상태: 승인됨
- 날짜: 2026-10-17

## 맥락
- 결정: remember-me는 **PersistentTokenBasedRememberMeServices, 30일**. 세션(ADR-005)의 유휴 타임아웃은 2시간이다.
- 체크박스 기본값은 해제, 로그아웃 시 모든 기기 해제(표준 동작), 토큰은 해시로 저장.
- 세션은 Spring Session JDBC에, 세션 쿠키 설정은 `SessionCookieConfig`에 있다.

## 결정
1. 로그인 화면의 "이 기기에서 로그인 유지 (30일)" 체크박스(기본 해제)를 켰을 때만 `remember-me` 쿠키를 발급한다.
2. 쿠키 속성: `Max-Age=2592000`(30일), `Secure`, `HttpOnly`(부모 클래스가 항상 켬), `SameSite=Lax`(`setCookieCustomizer`).
   - 실제 Tomcat 11 응답에 이 속성이 그대로 나오는 것을 `SessionCookieRealServerTests`로 확인했다.
3. **토큰은 해시로 저장**한다(`HashingPersistentTokenRepository` + `HashingRememberMeServices`).
   - 표준 구현은 DB에 토큰 원문을 저장하고 `presentedToken.equals(storedToken)`으로 비교한다.
   - 저장소는 저장할 때 SHA-256으로 바꾸고, 서비스는 쿠키의 원문을 SHA-256으로 바꿔 부모에게 넘긴다. 해시끼리 비교된다.
   - 토큰 회전(자동 로그인할 때마다 새 토큰), 도난 감지, 만료 처리는 Spring Security 표준 로직을 그대로 쓴다.
   - 테이블은 표준 이름(`persistent_logins`)을 쓰고, `last_used`만 `timestamptz`, `token`에 hex CHECK를 붙였다(V6).
4. **자동 로그인은 landlord id로 사용자를 찾는다**(`RememberMeUserDetailsService`).
   - 토큰에는 principal 이름(= landlord id, ADR-005)이 저장되므로, 이메일로 찾는 `LandlordUserDetailsService`로는 찾을 수 없다(Security 7.1.1 소스 135행 `loadUserByUsername(token.getUsername())`).
   - 로그인 잠금은 반영하지 않는다(ADR-012 B안). 탈퇴 등 계정 상태는 반영한다.
5. **도난 감지 시 500 대신 안내**: 이미 쓴 토큰이 다시 오면 부모가 그 사용자의 토큰을 모두 지우고 `CookieTheftException`을 던진다.
   - Spring Security 7.1.1의 `RememberMeAuthenticationFilter`는 `autoLogin()`을 try 밖에서 호출해 이 예외가 500 오류가 된다(소스 확인).
   - 그래서 `HashingRememberMeServices.autoLogin`에서 잡아 로그아웃 상태로 진행시키고, 로그인 화면에 "보안을 위해 이 기기의 로그인 유지를 해제했어요"를 한 번 보여 준다.
6. **정적 파일은 별도 보안 체인**(`staticResourcesFilterChain`, `@Order(1)`)으로 분리한다. 세션과 remember-me 없이 보안 헤더만 붙인다.
   - 이유: 세션이 만료된 상태에서 브라우저가 페이지와 CSS를 동시에 요청하면, 두 요청이 같은 쿠키로 동시에 자동 로그인을 시도한다. 늦은 쪽이 "이미 쓴 토큰"으로 보여 도난으로 오판되고, 모든 기기의 로그인 유지가 풀린다.
7. 자동 로그인이 성공하면 Spring Security가 SecurityContext를 세션에 저장한다(`securityContextRepository.saveContext`). 새 세션이 `spring_session`에 landlord id로 저장되는 것을 테스트로 확인했다.
8. 로그아웃하면 그 사용자의 모든 기기 토큰을 지운다(표준 `removeUserTokens`). 비밀번호 재설정·변경 때도 같은 저장소로 모두 지운다.
9. 30일 넘게 쓰이지 않은 토큰은 매일 04:10(KST)에 지운다.

## 키(APP_REMEMBER_ME_KEY)
- 운영에서는 필수(32자 이상)다. 없거나 짧거나 `${...}`처럼 치환되지 않은 값이면 앱이 뜨지 않는다.
- local에서 비어 있으면 실행할 때마다 임시 키를 쓴다(CI처럼 `.env`가 없는 곳에서도 테스트가 돌도록).
- 만드는 법(PowerShell 5.1): `$b = New-Object byte[] 32; [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); [Convert]::ToBase64String($b)`
- **키를 바꿔도 기존 로그인 유지는 풀리지 않는다.**
  - PersistentToken 방식에서 키는 자동 로그인 때 새로 만드는 인증 객체(`RememberMeAuthenticationToken`)를 서명·검증하는 데만 쓰인다. 만들 때와 검증할 때 모두 현재 키를 쓰므로 DB의 토큰과 무관하다(`RememberMeAuthenticationProvider` 58행).
  - 키 하나를 바꾸면 쿠키가 모두 무효가 되는 것은 `TokenBasedRememberMeServices`(해시 서명 쿠키) 방식의 성질이다. 이 프로젝트에는 해당하지 않는다. 테스트(`changingKeyDoesNotInvalidateExistingPersistentLogins`)로 확인했다.
  - **모든 기기의 로그인 유지를 끊으려면 `persistent_logins` 테이블을 비운다.**

## 검토한 대안
| 대안 | 채택하지 않은 이유 |
|---|---|
| 표준 `JdbcTokenRepositoryImpl` 그대로(평문) | DB가 유출되면 30일 안의 토큰으로 바로 로그인된다 |
| `TokenBasedRememberMeServices`(해시 서명 쿠키, 테이블 없음) | 서버에서 특정 기기만 끊을 수 없다. 비밀번호를 바꾸면 쿠키가 무효가 되긴 하지만 토큰 회전·도난 감지가 없다 |
| Spring Session `SpringSessionRememberMeServices`(세션을 30일로 연장) | 30일짜리 세션 행이 쌓이고 토큰 회전·도난 감지가 없다. Boot는 이것을 위한 쿠키 설정을 `CookieSerializer`를 직접 만들 때만 넣는데, 우리는 직접 정의해서(ADR-005) 수동 설정이 필요하다 |
| 모든 요청에서 자동 로그인 | 정적 파일 병렬 요청 때 도난 오탐이 난다 |

## 결과와 한계
- 여러 탭을 동시에 새로고침하면(세션 만료 후) 여전히 도난으로 오판될 수 있다. 그때는 로그인 화면 안내 후 다시 로그인하면 된다.
- 로그아웃하면 모든 기기가 해제된다. "이 기기만 해제"가 필요해지면 직접 구현해야 한다.
- 만료 판정은 Spring Security 내부의 `System.currentTimeMillis()`를 써서 주입된 Clock을 따르지 않는다. 테스트는 DB의 `last_used`를 과거로 바꿔서 확인한다.
