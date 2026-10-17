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
- 비밀값 커밋 금지. .env.example만 커밋.
- MVP 비목표(자동 입금확인, 앱, 전자계약, 결제 대행)는 만들지 않는다.

## 명령어
- 실행: ./gradlew bootRun (로컬 DB: docker compose -f docker/docker-compose.yml up -d)
- 테스트: ./gradlew test
- 포맷: ./gradlew spotlessApply

## 작업 방식
- 설계 결정은 docs/decisions/ 에 ADR로 기록.
- 마일스톤 종료 시 PRD 18장 학습 체크리스트로 개발자에게 질문할 것.
- 모르는 요구사항은 추측하지 말고 질문.

## Windows 명령어 (개발 환경: Windows / PowerShell)
- 로컬 DB 기동: `docker compose --env-file .env -f docker\docker-compose.yml up -d`
  (`-f`로 하위 폴더를 지정하면 compose가 `.env`를 `docker\`에서 찾으므로 `--env-file`을 명시한다. 최초 1회 `Copy-Item .env.example .env` 후 값 수정)
- 로컬 DB 중지: `docker compose --env-file .env -f docker\docker-compose.yml down`
- 실행: `.\gradlew.bat bootRun`
- 테스트: `.\gradlew.bat test` (Testcontainers 사용 → Docker Desktop 실행 필요)
- 전체 빌드(포맷 검사 + 테스트): `.\gradlew.bat build`
- 포맷: `.\gradlew.bat spotlessApply`
- HTTP 확인: `curl.exe -s http://localhost:8080/actuator/health` (PowerShell 5.1의 `curl`은 Invoke-WebRequest 별칭)
- 진행 상황과 다음 마일스톤 결정 사항: docs/progress.md
