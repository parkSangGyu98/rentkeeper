# 진행 상황과 이월 결정 사항

> 새 세션에서도 결정이 유실되지 않도록 저장소에 기록한다. 마일스톤을 시작할 때 이 문서를 먼저 읽는다.

## 마일스톤 현황
| 마일스톤 | 상태 | 비고 |
|---|---|---|
| M0 프로젝트 세팅 | 진행 중 (로컬 완료, CI 검증 보류) | GitHub 저장소 생성 후 push·CI 확인 |
| M1 인증과 기본 골격 | 대기 | |

## 저장소 공개 전 할 일
- [ ] 커밋 author 이메일을 GitHub noreply(`<id>+<username>@users.noreply.github.com`)로 교체 (현재는 전역 git 설정 이메일로 커밋됨)
- [ ] GitHub 저장소 생성 → remote 등록 → push (개발자 지시 후)
- [ ] CI(GitHub Actions) 첫 실행 통과 확인 → M0 완료 처리
- [ ] Dependabot 첫 PR 동작 확인
- [ ] `main` 브랜치 보호 규칙 설정 (CI 통과 필수)

## M1에서 반영할 인증 결정 (확정)
- 카카오 로그인에서 **이메일 미동의** 시: 가입 후 "알림 받을 이메일" 입력 + 인증 단계를 둔다.
- **같은 이메일이어도 계정을 자동으로 합치지 않는다** (이메일 가입 계정과 카카오 계정은 별개).
- **이메일 인증 전에도 로그인은 허용**하고, 미인증 상태는 배너로 안내한다.
- 세션 저장소: **Spring Session JDBC**.
- remember-me: **PersistentTokenBasedRememberMeServices, 30일**.
- 로그인 시도 제한: **계정 기준 5회 실패 시 15분 잠금 + IP 레이트 리미트(DB 저장)**.
- 스키마 변경: M1 계획 단계에서 **테이블 정의를 보여 주고 다시 승인**받는다.
- Tailwind는 **standalone CLI**(Node 불필요)로 M1 레이아웃 작업 때 도입한다.
- M1 시작 시 확인: Spring Security 7 + thymeleaf-extras-springsecurity 호환 버전, Boot 4 OAuth2 스타터 이름(`spring-boot-starter-security-oauth2-client`).

## M2로 이월
- **QueryDSL** 도입 — Hibernate 7(Boot 4.1.1 BOM: 7.4.5.Final) 호환 여부를 먼저 검토.
- **ArchUnit** 추가 — 패키지 의존 방향, `LocalDate.now()` 등 인자 없는 `now()` 호출 금지 규칙(ADR-002).

## M3로 이월
- 청구 상태 전이표(`PARTIALLY_PAID` 진입·이탈 조건, 취소 가능 상태)는 **M3 시작 전에 별도로 확정**한다.
- ShedLock의 Boot 4 호환 버전 확인.

## 기타 메모
- 포매터는 Eclipse 기본 프로필로 시작했다. 개발자의 Eclipse 포매터 XML을 받으면 `config/formatter/eclipse-formatter.xml`을 교체하고 `.\gradlew.bat spotlessApply`로 일괄 재포맷한다 (재포맷은 별도 커밋 `style:`).
  Eclipse의 Organize Imports 순서도 함께 내보내(`.importorder`) Spotless `importOrderFile(...)`로 맞추면 저장 시 import 순서가 흔들리지 않는다. 현재는 `java, javax, org, com, 기타` 순서로 임시 지정했다.
- Spotless의 Eclipse JDT 버전은 개발자 Eclipse(2025-06 = 4.36)에 맞춰 `eclipse("4.36")`으로 고정했다. Eclipse를 업그레이드하면 이 값도 함께 올린다.
- Gradle 9.8.1은 2주 규칙의 예외로 채택했다 (ADR-004).
