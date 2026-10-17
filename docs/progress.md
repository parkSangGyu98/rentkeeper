# 진행 상황과 이월 결정 사항

> 새 세션에서도 결정이 유실되지 않도록 저장소에 기록한다. 마일스톤을 시작할 때 이 문서를 먼저 읽는다.

## 마일스톤 현황
| 마일스톤 | 상태 | 비고 |
|---|---|---|
| M0 프로젝트 세팅 | 완료 (CI 통과) | |
| M1-a 보안 기본·이메일 가입/로그인·세션·레이아웃·정적 페이지 | 완료 (PR #2 병합, CI 통과, 브라우저 확인 완료) | |
| M1-b 이메일 인증 배너·비밀번호 재설정·로그인 잠금/IP 제한·remember-me·재인증 | 진행 중 (`feature/m1-b-auth-security`) | main 병합은 개발자 지시 |
| M1-c 카카오 로그인·회원 탈퇴/파기 배치·동의 기록 | 대기 | |

## 저장소 공개 전 할 일
- [ ] 커밋 author 이메일을 GitHub noreply(`<id>+<username>@users.noreply.github.com`)로 교체 (현재는 전역 git 설정 이메일로 커밋됨)
- [x] GitHub 저장소 생성 → remote 등록 → push (2026-10-17, Private)
- [x] CI(GitHub Actions) 첫 실행 통과 확인 → M0 완료 처리
- [x] Dependabot 첫 PR 동작 확인 (postgres 18 PR이 열림 → 병합하지 않고 닫음. postgres 메이저 업데이트는 dependabot.yml에서 무시)
- [ ] `main` 브랜치 보호 규칙 설정 (CI 통과 필수)

## 출시 전 필수 (차단 항목: 해결 전에는 운영 배포 금지)
- [ ] **클라이언트 IP 판정**: 지금 `ClientIp`는 `request.getRemoteAddr()`를 그대로 쓴다. 운영에서 Caddy 뒤에 두면 모든 요청이 프록시 IP로 보여 **전체 사용자가 같은 레이트 리미트에 묶인다**(로그인 10분 30회를 모든 사용자가 나눠 씀). M6에서 신뢰할 프록시를 정하고 `server.forward-headers-strategy`를 설정한 뒤, 프록시 뒤에서 IP별로 따로 세어지는지 확인한다. X-Forwarded-For를 아무 요청에서나 믿으면 IP 위조가 가능하므로 신뢰 범위를 함께 정한다 (ADR-012).
- [ ] **운영용 메일 발송 구현(M5)**: 없으면 prod 프로필로 앱이 기동하지 않는다 (ADR-010).

## 출시 전 할 일
- [ ] 이용약관·개인정보처리방침 **출시 전 전문가 검토** (현재 화면은 목차만 있는 초안 v0.1, 시행일 미정)
- [ ] M6: 운영 서버에서 BCrypt cost 12 해시 시간 측정 → 너무 느리면(대략 1초 이상) cost 재검토 (ADR-009)
- [ ] M6: 리버스 프록시(Caddy) 뒤에서 HTTPS 판정(`server.forward-headers-strategy`) 설정 → HSTS·Secure 쿠키 정상 동작 확인

## M1 분할 (2026-10-17 확정)
| 단계 | 범위 |
|---|---|
| M1-a | Spring Security 기본 구성, 이메일 회원가입/로그인/로그아웃(AUTH-1 일부), Spring Session JDBC, 공통 레이아웃(Tailwind standalone CLI), 에러 페이지, 약관·처리방침 정적 페이지(SET-2) |
| M1-b | 이메일 인증 배너, 비밀번호 재설정, 로그인 잠금·IP 레이트 리미트, remember-me, 민감 작업 재인증 |
| M1-c | 카카오 로그인, 회원 탈퇴와 파기 배치, 동의 기록(`consent_log`) |

### M1-a에서 확정한 결정
- 이메일 컬럼: `email` 하나 + `auth_provider`(LOCAL/KAKAO), **LOCAL 계정끼리만 unique**. 앱에서 소문자 정규화 + DB CHECK로 강제 (ADR-006)
- 중복 이메일 가입 시 "이미 가입된 이메일이에요"로 안내. 계정 존재 노출 위험은 M1-b IP 레이트 리미트로 완화
- BCrypt cost 12, `{bcrypt}` 접두사(DelegatingPasswordEncoder)
- 세션 유휴 타임아웃 2시간, 쿠키 `HttpOnly`·`Secure`(로컬도 true)·`SameSite=Lax`
- 가입 직후 바로 로그인
- 가입 폼: 이메일, 비밀번호, 비밀번호 확인, 이름(호칭), 약관·처리방침 필수 동의(검증만, 기록은 M1-c)
- 흔한 비밀번호 목록은 직접 정리한 약 100개
- 세션 principal 이름은 이메일이 아니라 landlord id

### M1-a 구현 결과 메모 (2026-10-17)
- 마이그레이션: V2 `landlord`, V3 Spring Session 테이블(공식 스크립트 그대로). ADR-005~009 작성.
- 세션 설정은 `spring.session.timeout`과 `SessionCookieConfig`(CookieSerializer 빈) 한 곳에만 둔다. Boot가 MockMvc와 실제 실행에서 `server.servlet.session.*`를 다른 경로로 읽기 때문이다 (ADR-005). 실제 Tomcat 테스트는 `SessionCookieRealServerTests` 1개뿐.
- `thymeleaf-extras-springsecurity6`는 Security 7.1.1에서 `sec:authorize`·`sec:authentication`이 동작하는 것을 테스트로 확인했다 → `@ControllerAdvice` 대체는 필요 없었다.
- 기본 거부(`denyAll`) 때문에 비로그인 상태로 없는 주소에 가면 404가 아니라 로그인 화면으로 간다(로그인 상태면 403). 404 화면은 `/css/no-such.css`처럼 허용 경로 안의 없는 파일에서 보인다 (ADR-008).
- Tailwind 4.3.3 Windows 바이너리는 이 PC에서, Linux 바이너리는 CI(PR #2)에서 동작 확인.
- Windows의 `curl.exe`로 한글 폼 값을 명령줄에서 보내면 CP949로 바뀌어 Tomcat이 400을 준다. 브라우저(UTF-8)는 정상이다. curl로 시험할 때는 `%EA%B2%80`처럼 UTF-8 퍼센트 인코딩 값을 직접 넣는다.
- 확인 완료: 개발자 브라우저 확인(가입→로그인→로그아웃, 쿠키 속성, 404 화면), CI 통과, PR #2로 main 병합.

### b/c에서 추가할 스키마 (모두 추가만, M1-a 구조는 바꾸지 않음)
- **M1-b**: `landlord.email_verified_at`, 이메일 인증 토큰 테이블, 비밀번호 재설정 토큰 테이블(30분, 1회용), 로그인 시도·IP 제한 테이블, `persistent_logins`(remember-me)
- **M1-c**: `landlord.kakao_id`(KAKAO 계정끼리 unique), `landlord.withdrawn_at`, KAKAO 계정이면 `kakao_id` 필수인 CHECK, `consent_log` 테이블
- **M1-c에서 정할 것**: 탈퇴 후 30일 유예 중 같은 이메일 재가입 허용 여부 → 결과에 따라 `uk_landlord_local_email` 인덱스 조건만 재생성
- **M2**: `landlord.phone_enc` (암호화 컨버터와 함께, SET-1)

### M1-b로 미룬 것
- 메일 발송 인터페이스(`MailSender` 계열)와 로그 출력 구현체
- 비밀번호 변경 시 그 사용자의 모든 세션 종료 (`spring_session.principal_name` 인덱스 활용)

## M1에서 반영할 인증 결정 (확정)
- 카카오 로그인에서 **이메일 미동의** 시: 가입 후 "알림 받을 이메일" 입력 + 인증 단계를 둔다.
- **같은 이메일이어도 계정을 자동으로 합치지 않는다** (이메일 가입 계정과 카카오 계정은 별개).
- **이메일 인증 전에도 로그인은 허용**하고, 미인증 상태는 배너로 안내한다. 알림은 인증된 주소로만 보낸다.
- 세션 저장소: **Spring Session JDBC**.
- remember-me: **PersistentTokenBasedRememberMeServices, 30일**.
- 로그인 시도 제한: **계정 기준 5회 실패 시 15분 잠금 + IP 레이트 리미트(DB 저장)**.
- 민감 작업 재인증: 비밀번호 변경, 이메일 변경, 탈퇴.
- 스키마 변경: 각 단계 계획에서 **테이블 정의를 보여 주고 다시 승인**받는다.
- 카카오 로그인(M1-c) 시작 시 확인: Boot 4 OAuth2 스타터 이름(`spring-boot-starter-security-oauth2-client`), CSP `form-action`과 OAuth 리다이렉트 동작.

## M2로 이월
- **QueryDSL** 도입 — Hibernate 7(Boot 4.1.1 BOM: 7.4.5.Final) 호환 여부를 먼저 검토.
- **ArchUnit** 추가 — 패키지 의존 방향, `LocalDate.now()` 등 인자 없는 `now()` 호출 금지 규칙(ADR-002).
- 임대인 소유 리소스가 생기므로 IDOR(타 계정 접근) 테스트 작성 (M1 완료 조건 중 "타 계정 접근 테스트"는 M1-a에서 비로그인 차단까지만 다룸).

## M3로 이월
- 청구 상태 전이표(`PARTIALLY_PAID` 진입·이탈 조건, 취소 가능 상태)는 **M3 시작 전에 별도로 확정**한다.
- ShedLock의 Boot 4 호환 버전 확인.

## 기타 메모
- 포매터는 Eclipse 기본 프로필로 시작했다. 개발자의 Eclipse 포매터 XML을 받으면 `config/formatter/eclipse-formatter.xml`을 교체하고 `.\gradlew.bat spotlessApply`로 일괄 재포맷한다 (재포맷은 별도 커밋 `style:`).
  Eclipse의 Organize Imports 순서도 함께 내보내(`.importorder`) Spotless `importOrderFile(...)`로 맞추면 저장 시 import 순서가 흔들리지 않는다. 현재는 `java, javax, org, com, 기타` 순서로 임시 지정했다.
- Spotless의 Eclipse JDT 버전은 개발자 Eclipse(2025-06 = 4.36)에 맞춰 `eclipse("4.36")`으로 고정했다. Eclipse를 업그레이드하면 이 값도 함께 올린다.
- Gradle 9.8.1은 2주 규칙의 예외로 채택했다 (ADR-004).
- PowerShell 5.1에서 한글 커밋 메시지는 `-m`으로 넘기면 깨질 수 있다. UTF-8 파일로 만들어 `git commit -F <파일>`로 커밋한다.
