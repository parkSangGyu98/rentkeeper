# RentKeeper

소규모 임대인용 월세 관리 웹 서비스. 상세 기획은 docs/PRD.md 참조.

## 스택
Java 21, Spring Boot 4.1, Gradle(Kotlin DSL), JPA+QueryDSL, PostgreSQL 16, Flyway,
Thymeleaf+HTMX+Tailwind, Spring Security(세션), JUnit5+Testcontainers

## 반드시 지킬 것
- 구현 전에 계획 제시 → 승인 후 진행. 한 번에 하나의 마일스톤만.
- 금액은 long(원). 시간은 Clock 주입, LocalDate.now() 직접 호출 금지. 시간대 Asia/Seoul.
- 모든 임대인 소유 데이터 접근은 landlord_id 검증 필수. 타인 데이터는 404.
- 개인정보(이름·연락처·계좌)는 암호화 저장, 로그에는 마스킹.
- 청구 생성·알림 발송은 멱등해야 한다 (UNIQUE 제약 + 테스트).
- 상태 변경은 도메인 메서드로만, 전이 규칙은 테스트로 검증.
- 스키마 변경은 Flyway 마이그레이션으로만. 기존 마이그레이션 파일 수정 금지.

## DB 설명(COMMENT) 규칙
- 새로 만드는 모든 테이블과 컬럼에 `COMMENT ON TABLE` / `COMMENT ON COLUMN`으로 **한글 설명**을 붙인다. 같은 마이그레이션 파일 안에 쓴다.
  SQL 파일의 `--` 주석은 DB에 저장되지 않아 DBeaver 등에서 보이지 않는다.
- 설명에는 "무엇을 담는지"와 함께, 헷갈릴 수 있는 컬럼은 "왜 필요한지"나 값의 뜻을 적는다.
  - 상태·코드값: 각 값의 의미 (예: `ACTIVE=사용 중, WITHDRAWN=탈퇴 처리됨`)
  - NULL의 의미 (예: `NULL=미인증`)
  - 단위 (원, 초, 밀리초)
  - 시각 컬럼: UTC로 저장한다는 점
- 목적이 분명하지 않은 CHECK·UNIQUE 제약과 인덱스는 `COMMENT ON CONSTRAINT` / `COMMENT ON INDEX`로 짧게 설명한다.
- 이미 적용·커밋한 마이그레이션은 설명을 추가하려고 고치지 않는다(Flyway 체크섬). 새 마이그레이션으로 추가한다.
- `SchemaCommentsTests`가 설명이 빠진 테이블·컬럼을 찾으면 빌드가 실패한다 (`flyway_schema_history` 제외).
- 비밀값 커밋 금지. .env.example만 커밋.
- MVP 비목표(자동 입금확인, 앱, 전자계약, 결제 대행)는 만들지 않는다.

## 명령어
- 실행: ./gradlew bootRun (로컬 DB: docker compose -f docker/docker-compose.yml up -d)
- 테스트: ./gradlew test
- 포맷: ./gradlew spotlessApply

## 작업 방식
- 설계 결정은 docs/decisions/ 에 ADR로 기록.
- 모르는 요구사항은 추측하지 말고 질문.
- 마일스톤 종료 시 퀴즈·학습 체크리스트는 내지 않는다 (서비스 운영 목적).

## 커밋 규칙
- 형식: `타입: 한글 요약` (예: `feat: 이메일 회원가입 추가`). 타입은 영어 유지: feat, fix, docs, test, refactor, chore, build, ci. 범위가 필요하면 `feat(auth): ...`.
- 본문(필요할 때): 무엇을, 왜 바꿨는지 쉬운 한글로. 나중에 다시 읽고 이해할 수 있게 쓴다.
- 이미 push한 기존 영어 커밋은 그대로 둔다. 이력 재작성·강제 push 금지.
- 단계별로 나눠 커밋하고, push는 개발자가 지시할 때만 한다.

## Windows 명령어 (개발 환경: Windows / PowerShell)
- 로컬 DB 기동: `docker compose --env-file .env -f docker\docker-compose.yml up -d`
  (`-f`로 하위 폴더를 지정하면 compose가 `.env`를 `docker\`에서 찾으므로 `--env-file`을 명시한다. 최초 1회 `Copy-Item .env.example .env` 후 값 수정)
- 로컬 DB 중지: `docker compose --env-file .env -f docker\docker-compose.yml down`
- 실행: `.\gradlew.bat bootRun`
- 화면 개발 중 CSS 자동 재빌드: 다른 터미널에서 `.\gradlew.bat tailwindWatch` (local 프로필은 템플릿·CSS 변경을 재시작 없이 반영)
- 테스트: `.\gradlew.bat test` (Testcontainers 사용 → Docker Desktop 실행 필요)
- 전체 빌드(포맷 검사 + 테스트): `.\gradlew.bat build`
- 포맷: `.\gradlew.bat spotlessApply`
- HTTP 확인: `curl.exe -s http://localhost:8080/actuator/health` (PowerShell 5.1의 `curl`은 Invoke-WebRequest 별칭)
- 진행 상황과 다음 마일스톤 결정 사항: docs/progress.md
