# 소규모 임대인 월세 관리 서비스 기획서 (PRD)

> **문서 목적**: 이 문서는 Claude Code가 읽고 개발을 수행하기 위한 상세 기획서입니다.
> **서비스 가칭**: RentKeeper (최종 이름 미정, 코드에서는 `rentkeeper` 사용)
> **개발자**: 1인 (Java/Spring 백엔드 4년차, 맥북 없음 → 웹/PWA 우선)
> **문서 버전**: v0.1
> **변경 이력**: 2026-10-17 3.1 프레임워크 Spring Boot 3.x → 4.1.x (3.5 OSS 지원 종료, ADR-004)
> **변경 이력**: 2026-10-17 0장 8번·18장 학습 체크리스트 폐지 (서비스 운영 목적)

---

## 0. Claude Code 작업 원칙 (가장 먼저 읽을 것)

1. **개발자는 이 프로젝트를 학습·포트폴리오 목적으로도 사용한다.** 코드를 그냥 만들어 주지 말고, 각 단계마다 "왜 이렇게 설계했는지"를 짧게 설명하고 `docs/decisions/`에 ADR(설계 결정 기록)을 남긴다.
2. **한 번에 하나의 마일스톤만** 진행한다. 범위를 넘는 기능(자동 입금확인, 앱, 전자계약 등)은 절대 선제적으로 만들지 않는다.
3. **구현 전에 계획을 먼저 제시**하고 개발자 승인을 받는다. 특히 DB 스키마 변경, 보안 관련 결정, 외부 서비스 연동은 반드시 먼저 묻는다.
4. **도메인 로직은 테스트 먼저(또는 동시에)** 작성한다. 청구 생성, 상태 전이, 알림 중복 방지, 권한 검증은 테스트 없이 머지하지 않는다.
5. **모르는 요구사항은 추측하지 말고 질문**한다. 이 문서에 없는 정책은 "미정"으로 표시하고 개발자에게 확인한다.
6. **금액은 절대 부동소수점을 쓰지 않는다.** 원화는 `long`(원 단위 정수)으로 저장한다.
7. 비밀값(API 키, DB 비밀번호, 암호화 키)은 코드·로그·커밋에 절대 포함하지 않는다.
8. 폐지됨(서비스 운영 목적)

---

## 1. 제품 개요

### 1.1 한 줄 정의
다가구·상가 등을 소유한 소규모 임대인이 월세 청구, 입금 확인, 미납 알림, 계약 만료 관리를 **엑셀과 카톡 없이** 자동으로 처리하는 웹 서비스.

### 1.2 해결하려는 문제
- 임대인은 호실별 납부일, 금액, 관리비, 계약 만료일을 엑셀·수첩·기억에 의존한다.
- 월세 독촉 문자를 직접 보내는 일이 심리적으로 불편하다.
- 계약 갱신 시기(만료 3개월 전 등)를 놓친다.
- 연말·종합소득세 시기에 연간 수입 정리가 번거롭다.

### 1.3 타깃 사용자
| 구분 | 설명 | 비고 |
|---|---|---|
| **임대인 (결제 고객)** | 다가구/원룸/상가 소유주, 5~30호실, 40~60대 다수 | 모바일 웹 + PC 사용, IT 숙련도 낮음 |
| **세입자 (보조 사용자)** | 임대인이 등록한 세입자 | **가입 없이 링크로만 접근** |

> 설계 원칙: 임대인 UI는 **글씨 크고, 단계가 적고, 용어가 쉬워야 한다.** (예: "청구" → "이번 달 받을 월세")

### 1.4 핵심 가치
1. 매달 청구서를 **자동으로 만들고 보내 준다.**
2. 미납을 **자동으로 알려 주고 추적**해 준다.
3. 계약 만료를 **미리 알려 준다.**
4. 연간 수입을 **엑셀로 뽑아 준다.**

### 1.5 비목표 (Non-goals, MVP에서 하지 않는 것)
- 계좌/오픈뱅킹 연동 자동 입금 확인
- 전자계약, 전자서명
- 카드·계좌 자동이체, 세입자 결제 대행 (PG로 월세를 대신 받는 기능)
- 네이티브 앱 (iOS/Android)
- 세금 신고 연동, 세무 조언
- 임대차 분쟁 상담, 법률 문서 자동 작성
- 중개/매물 기능
- 다국어

---

## 2. 용어 정의

| 용어 | 정의 |
|---|---|
| 임대인 (Landlord) | 서비스에 가입하고 결제하는 고객 |
| 건물 (Property) | 임대인이 소유한 건물 단위 (예: 행복빌라) |
| 호실 (Unit) | 건물 내 임대 단위 (예: 101호) |
| 세입자 (Tenant) | 호실을 임차하는 사람. 시스템 로그인 계정이 없다. |
| 계약 (Lease) | 호실-세입자 간 임대차 계약 정보 (보증금, 월세, 납부일, 기간) |
| 청구 (Charge) | 특정 월에 세입자에게 받아야 할 금액 묶음 |
| 청구 항목 (ChargeItem) | 월세, 관리비, 전기, 수도 등 청구의 세부 항목 |
| 입금 기록 (PaymentRecord) | 청구에 대해 실제 입금된 내역(수동 입력 기반) |
| 청구월 (billingMonth) | 청구가 해당하는 월 (예: 2026-11) |
| 납부일 (dueDay) | 매월 납부 기한 일자 (1~31) |
| 세입자 링크 | 세입자가 가입 없이 청구서를 볼 수 있는 토큰 URL |

---

## 3. 기술 스택

### 3.1 확정 스택

| 영역 | 선택 | 선택 이유 |
|---|---|---|
| 언어 | **Java 21 (LTS)** | 개발자의 주력 스택, 금융/SI 이직 방향과 일치 |
| 프레임워크 | **Spring Boot 4.1.x** | 표준, 풍부한 생태계. 3.5는 2026-06-30 OSS 지원 종료 (ADR-004) |
| 빌드 | **Gradle (Kotlin DSL)** | 표준 |
| ORM | **Spring Data JPA + QueryDSL** | 도메인 모델링, 이직 시 면접 연계 |
| DB | **PostgreSQL 16** | 트랜잭션·제약조건 강력, 확장(pgvector 등) 여지 |
| 마이그레이션 | **Flyway** | 스키마 버전 관리 |
| 화면 | **Thymeleaf + HTMX + Tailwind CSS** (서버사이드 렌더링) | 단일 배포 단위, 유지보수 쉬움, 세입자 링크 페이지에 적합, 프런트 별도 프로젝트 불필요 |
| 인증 | **Spring Security** (세션 기반 + 카카오 OAuth2) | 서버 렌더링 앱엔 세션이 단순하고 안전 |
| 스케줄러 | **Spring `@Scheduled` + ShedLock** | 배치 중복 실행 방지 (향후 서버 증설 대비) |
| 비동기 | Spring `@Async` + DB 기반 발송 큐(Outbox 패턴) | MVP에서 별도 MQ 불필요 |
| 캐시 | MVP에서는 **미사용** (필요해질 때 Redis 도입) | 과설계 방지 |
| 파일 저장 | **S3 호환 스토리지** (AWS S3 또는 Cloudflare R2) | 계약서·고지서 사진 |
| 알림 | **알림톡/문자 대행사 API** (Solapi 등, `NotificationSender` 인터페이스로 추상화) | 4.5절 참조 |
| 결제(구독) | **토스페이먼츠 또는 포트원** (Phase 3) | 사업자 등록 필요 |
| 테스트 | **JUnit 5, AssertJ, Mockito, Testcontainers(PostgreSQL), Spring Security Test** | 실제 DB 기반 통합 테스트 |
| 정적 분석 | Spotless(포맷), Checkstyle 또는 SpotBugs (선택) | 일관성 |
| 컨테이너 | **Docker, Docker Compose** | 로컬·배포 일치 |
| CI/CD | **GitHub Actions** | 빌드·테스트·배포 자동화 |
| 모니터링 | Spring Actuator, **Sentry**(에러), UptimeRobot(가용성) | 무료 티어 |
| 로깅 | SLF4J + Logback (JSON 구조 로그 권장) | 개인정보 마스킹 필수 |

> **왜 React/Next.js가 아닌가?** 개발자는 백엔드 개발자이고 혼자 유지보수한다. SSR + HTMX는 코드베이스가 하나라 배포·디버깅이 단순하다. 나중에 앱/외부 연동이 필요하면 `/api/v1` REST 계층을 추가하는 것으로 충분하다. (ADR-001로 기록할 것)

### 3.2 배포/인프라 (MVP)
- **서버**: 단일 VM 1대 (AWS Lightsail 또는 EC2 t4g.small, 서울 리전) 또는 동급 PaaS
- **구성**: Docker Compose (`app`, `postgres`, `caddy`(리버스 프록시 + 자동 HTTPS))
- **DB 백업**: 매일 `pg_dump` → 스토리지 업로드, 7일 이상 보관, **복구 테스트 1회 이상**
- **도메인/HTTPS**: 필수
- **환경**: `local`, `prod` 두 개로 시작 (`staging`은 사용자 생긴 후)
- **시간대**: 서버와 JVM 모두 `Asia/Seoul`로 고정, DB 타임스탬프는 UTC 저장 후 변환 (ADR로 기록)

### 3.3 외부 서비스 정책
| 서비스 | MVP 사용 여부 | 비고 |
|---|---|---|
| 카카오 로그인 | 사용 | OAuth2, 이메일 로그인과 병행 |
| 알림톡 | **M5에서 연동** | 카카오 비즈채널·사업자등록·템플릿 심사 필요. 그 전에는 `LogNotificationSender`(로그 출력)와 이메일로 개발 |
| 문자(SMS/LMS) | M5 | 알림톡 실패 시 대체 발송 옵션 |
| 이메일 | 사용 | 임대인 대상 알림 (SES 또는 SMTP) |
| 결제 PG | **Phase 3** | 사업자등록 후 |
| 오픈뱅킹 | **사용 안 함** | |

---

## 4. 기능 요구사항

> 우선순위: **P0** = MVP 필수, **P1** = 출시 직후, **P2** = 사용자 생긴 후

### 4.1 인증/계정

| ID | 기능 | 우선순위 | 상세 |
|---|---|---|---|
| AUTH-1 | 이메일 회원가입/로그인 | P0 | 비밀번호 BCrypt, 최소 8자, 이메일 인증 |
| AUTH-2 | 카카오 로그인 | P0 | 이메일 미동의 시에도 가입 가능하도록 처리 |
| AUTH-3 | 비밀번호 재설정 | P0 | 이메일 링크, 토큰 30분 만료, 1회용 |
| AUTH-4 | 로그아웃/세션 관리 | P0 | 세션 타임아웃 30일(remember-me), 민감 작업은 재인증 |
| AUTH-5 | 회원 탈퇴 | P0 | 소프트 삭제 후 30일 뒤 개인정보 완전 파기(배치) |
| AUTH-6 | 약관/개인정보 동의 기록 | P0 | `consent_log`에 버전·시각 저장 |
| AUTH-7 | 로그인 시도 제한 | P0 | IP+계정 기준 5회 실패 시 지연/잠금 |

### 4.2 건물/호실 관리

| ID | 기능 | 우선순위 | 상세 |
|---|---|---|---|
| PROP-1 | 건물 CRUD | P0 | 이름, 주소, 메모 |
| PROP-2 | 호실 CRUD | P0 | 호실명(101호), 유형(원룸/투룸/상가 등), 메모. 건물 내 호실명 중복 불가 |
| PROP-3 | 호실 상태 | P0 | 공실/입주중. 계약 유무로 자동 계산 |
| PROP-4 | 호실 일괄 등록 | P1 | "101~110호" 입력 시 일괄 생성 |
| PROP-5 | 플랜 제한 확인 | P1 | 무료 플랜 호실 수 초과 시 안내 (Phase 3 결제와 연계) |

### 4.3 세입자/계약 관리

| ID | 기능 | 우선순위 | 상세 |
|---|---|---|---|
| LEASE-1 | 계약 등록 | P0 | 호실, 세입자(이름·연락처), 보증금, 월세, 관리비(고정), 납부일, 시작일/종료일, 입금 계좌 |
| LEASE-2 | 계약 수정 | P0 | 월세 변경 시 **변경 이력** 저장, 적용 시작 월 지정 (과거 청구는 변경하지 않음) |
| LEASE-3 | 계약 종료 | P0 | 종료 처리 시 이후 청구 생성 중단, 호실 공실 전환. 미납 청구는 유지 |
| LEASE-4 | 호실당 활성 계약 1개 제약 | P0 | 기간이 겹치는 활성 계약 등록 불가 (DB + 서비스 레벨 검증) |
| LEASE-5 | 계약서 파일 첨부 | P1 | PDF/이미지, 최대 10MB, 파일당 확장자·MIME 검증 |
| LEASE-6 | 계약 갱신 | P1 | 종료일 연장 + 월세 변경을 하나의 흐름으로 처리 |
| LEASE-7 | 보증금 이력 | P1 | 증액/반환 기록 |
| LEASE-8 | 일할 계산(첫 달/마지막 달) | P1 | MVP에서는 **미지원**, 수동으로 청구 금액 수정 가능하게 처리 |

**입력 검증 규칙**
- 월세·관리비: 0 이상 정수, 최대 1억 원
- 납부일: 1~31. **해당 월에 없는 날짜(예: 31일, 2월)는 그 달 말일로 보정**
- 종료일은 시작일 이후
- 연락처: 숫자만 정규화 저장 (010XXXXXXXX), 형식 검증

### 4.4 월 청구/납부 관리 (핵심)

#### 4.4.1 청구 자동 생성

| ID | 기능 | 우선순위 |
|---|---|---|
| CHG-1 | 청구 자동 생성 배치 | P0 |
| CHG-2 | 청구 수동 생성/수정/취소 | P0 |
| CHG-3 | 청구 항목 추가(관리비, 공과금, 기타) | P0 |
| CHG-4 | 고지서 사진 첨부 | P1 |
| CHG-5 | 항목 템플릿(매월 반복되는 항목) | P2 |

**배치 규칙**
- 매일 **오전 3시(KST)** 실행: 활성 계약 중 "청구 생성 시점"(납부일 N일 전, 기본 7일 전)이 도래했고 해당 `billingMonth` 청구가 없는 건을 생성
- **멱등성 보장**: `UNIQUE(lease_id, billing_month)` 제약 + `INSERT ... ON CONFLICT DO NOTHING` 또는 예외 처리. 배치를 몇 번 실행해도 청구는 1건
- **실패 격리**: 계약 단위로 트랜잭션을 분리해 한 건 실패가 전체를 막지 않게 함. 실패는 로그/알림
- **누락 복구**: 배치가 이틀 못 돌아도 다음 실행에서 놓친 청구를 모두 생성(과거 `billingMonth` 보정 포함)
- 청구 금액 = 계약의 월세 + 고정 관리비 (변경 이력 기준으로 해당 월 적용 금액 산정)
- 계약 시작월/종료월 경계 처리는 ADR로 정책 확정 (기본안: 시작일이 속한 달부터 청구, 종료일이 속한 달까지 청구, 일할 계산 없음)

#### 4.4.2 청구 상태 모델

```
SCHEDULED ──발송──▶ ISSUED ──세입자 "입금했어요"──▶ TENANT_REPORTED
    │                  │                                  │
    │                  └──────────임대인 입금확인──────────┤
    │                                                     ▼
    │                                                   PAID
    │               ISSUED/TENANT_REPORTED ──납부일 경과──▶ OVERDUE ──입금확인──▶ PAID
    │
    └──취소──▶ CANCELED   (SCHEDULED/ISSUED/OVERDUE에서 가능, PAID는 취소 불가)
```

| 상태 | 의미 |
|---|---|
| SCHEDULED | 생성됨, 아직 세입자에게 발송 전 |
| ISSUED | 세입자에게 청구서 발송 완료 |
| TENANT_REPORTED | 세입자가 "입금했어요" 클릭, 임대인 확인 대기 |
| PARTIALLY_PAID | 일부만 입금됨 (잔액 > 0) |
| PAID | 전액 입금 확인됨 |
| OVERDUE | 납부일 경과, 미완납 |
| CANCELED | 취소 |

**상태 전이 규칙** (코드에 `ChargeStatus.canTransitionTo()`로 구현하고 전이표를 테스트할 것)
- 허용되지 않은 전이는 예외 발생
- `OVERDUE`는 일 배치(오전 0시 10분)가 `dueDate < today` AND 상태가 `ISSUED/TENANT_REPORTED/PARTIALLY_PAID`인 건에 대해 전환
- 입금 기록 합계가 청구 총액 이상이면 자동으로 `PAID`
- 상태 변경은 모두 **이력 테이블(`charge_status_history`)**에 기록

#### 4.4.3 입금 확인 (MVP는 수동)

| ID | 기능 | 우선순위 |
|---|---|---|
| PAY-1 | 임대인이 "입금 완료" 처리 (전액) | P0 |
| PAY-2 | 부분 입금 기록(금액, 입금일, 메모) | P0 |
| PAY-3 | 세입자 "입금했어요" 신고 → 임대인에게 알림 | P0 |
| PAY-4 | 입금 기록 삭제/정정(감사 로그 남김) | P0 |
| PAY-5 | 월 일괄 입금 확인 화면(체크박스) | P1 |
| PAY-6 | 입금 내역 텍스트/엑셀 붙여넣기 매칭(은행 앱에서 복사) | P2 |

**동시성**: 같은 청구에 대해 입금 처리가 동시에 들어올 수 있으므로 `charge.version`(낙관적 락)을 사용하고, 충돌 시 사용자에게 "다시 시도" 안내. 테스트로 검증할 것.

### 4.5 알림

| ID | 기능 | 우선순위 |
|---|---|---|
| NOTI-1 | 청구서 발송(세입자) | P0 |
| NOTI-2 | 납부일 당일/전일 리마인드(세입자) | P0 |
| NOTI-3 | 미납 알림 D+1, D+3, D+7(세입자) | P0 |
| NOTI-4 | 임대인 알림: 입금 신고 접수, 미납 발생, 계약 만료 임박 | P0 |
| NOTI-5 | 계약 만료 알림 D-90/D-60/D-30(임대인) | P0 |
| NOTI-6 | 알림 규칙 설정(임대인이 계약별로 on/off, 시점 조정) | P1 |
| NOTI-7 | 발송 이력 조회 | P0 |
| NOTI-8 | 알림톡 실패 시 SMS 대체 발송 | P1 |

**발송 구조 (Outbox 패턴)**
1. 업무 로직이 `notification` 테이블에 `PENDING` 레코드를 **같은 트랜잭션 안에서** 생성
2. 별도 발송 워커(스케줄러, 30초 주기)가 `PENDING`을 가져와 `NotificationSender`로 발송
3. 성공 `SENT`, 실패 시 지수 백오프 재시도(최대 3회) 후 `FAILED`
4. `dedupe_key = {type}:{charge_id}:{scheduled_date}` 에 **UNIQUE 제약** → 중복 발송 원천 차단
5. 발송 시 `SELECT ... FOR UPDATE SKIP LOCKED`로 워커 간 경합 방지

**알림 발송 제약**
- 발송 허용 시간: **오전 9시 ~ 오후 8시(KST)**. 범위 밖 예약은 다음 허용 시간으로 이월 (정책은 설정값으로)
- 세입자 수신 거부 시 이후 발송 중단(링크 내 수신거부 버튼)
- 알림 문구는 **정중하고 중립적**이어야 한다 (채무 독촉처럼 보이는 표현 금지). 문구 템플릿은 `docs/notification-templates.md`에 관리
- 알림톡 템플릿은 카카오 심사를 통과해야 하므로 변수 치환 방식(`#{이름}`, `#{금액}` 등)으로 설계

**알림 템플릿 초안**
- 청구서: `[#{건물명} #{호실}] #{월}월 월세 #{금액}원 안내드립니다. 납부기한: #{납부일}. 자세히 보기: #{링크}`
- 리마인드: `[#{건물명} #{호실}] 내일(#{납부일})이 #{월}월 월세 납부일입니다. #{링크}`
- 미납: `[#{건물명} #{호실}] #{월}월 월세가 아직 확인되지 않았습니다. 이미 납부하셨다면 아래 링크에서 알려 주세요. #{링크}`

### 4.6 대시보드/리포트 (임대인)

| ID | 기능 | 우선순위 |
|---|---|---|
| DASH-1 | 이번 달 현황: 입금/미납/대기 건수와 금액 | P0 |
| DASH-2 | 미납자 목록(미납일수 정렬) | P0 |
| DASH-3 | 계약 만료 임박 목록 | P0 |
| DASH-4 | 공실 현황 | P0 |
| DASH-5 | 월별 수입 추이(최근 12개월) | P1 |
| DASH-6 | 연간 수입 엑셀 내보내기(건물/호실/월별) | P1 |
| DASH-7 | 청구서/납부확인서 PDF 출력 | P1 |

**성능**: 대시보드 집계는 MVP에서 쿼리 직접 집계 + 인덱스로 충분. 호실 수가 늘어 느려지면 그때 비정규화/캐시 도입 (측정 후 판단).

### 4.7 세입자 링크 화면 (가입 없음)

| ID | 기능 | 우선순위 |
|---|---|---|
| TEN-1 | 토큰 URL 접속: 이번 달 청구서(항목별 금액, 입금 계좌, 납부기한) | P0 |
| TEN-2 | 계좌번호 복사 버튼 | P0 |
| TEN-3 | "입금했어요" 버튼 (중복 클릭 방지, 멱등) | P0 |
| TEN-4 | 납부 이력 조회(최근 12개월) | P0 |
| TEN-5 | 납부확인서 PDF | P1 |
| TEN-6 | 알림 수신 거부 | P0 |

**세입자 링크 보안**
- 토큰: `SecureRandom` 32바이트 → Base64URL. **DB에는 SHA-256 해시만 저장**
- 토큰은 계약(lease) 단위로 발급, 만료일 = 계약 종료일 + 90일, 임대인이 **재발급/폐기** 가능
- 링크 접속에 개인정보(전체 이름·전체 전화번호)를 노출하지 않음 (이름은 "김○○" 마스킹)
- 토큰 조회 엔드포인트에 **레이트 리미트** (IP당 분당 30회) 및 검색엔진 차단(`noindex`, `Referrer-Policy: no-referrer`)
- 청구서 링크는 모든 조회가 해당 `lease_id`에 한정되도록 쿼리 강제

### 4.8 설정/기타

| ID | 기능 | 우선순위 |
|---|---|---|
| SET-1 | 임대인 프로필, 알림 수신 이메일/연락처 | P0 |
| SET-2 | 약관·개인정보처리방침 페이지 | P0 |
| SET-3 | 문의하기(이메일 폼) | P0 |
| SET-4 | 데이터 내보내기(전체 JSON/엑셀) | P1 |
| SET-5 | PWA(홈 화면 추가, 매니페스트, 서비스 워커 최소) | P1 |
| SET-6 | 구독/결제 | P2 (Phase 3) |

---

## 5. 데이터 모델

> 공통: 모든 테이블에 `created_at`, `updated_at`(UTC). PK는 `bigint` identity. 외부 노출 ID가 필요하면 `public_id`(UUID) 별도 컬럼. 금액은 `bigint`(원).
> **멀티테넌시**: 임대인 소유 데이터에는 모두 `landlord_id`를 포함하고, 모든 조회/수정은 `landlord_id` 조건을 강제한다.

### 5.1 테이블

**landlord**
| 컬럼 | 타입 | 비고 |
|---|---|---|
| id | bigint PK | |
| email | varchar UNIQUE nullable | 카카오만 쓰는 경우 null 가능 |
| password_hash | varchar nullable | BCrypt |
| kakao_id | varchar UNIQUE nullable | |
| name | varchar | |
| phone_enc | varchar nullable | 암호화 |
| status | varchar | ACTIVE / WITHDRAWN |
| plan | varchar | FREE (기본), PRO (Phase 3) |
| withdrawn_at | timestamptz nullable | 파기 배치 기준 |

**property** — id, landlord_id FK, name, address, memo

**unit** — id, property_id FK, landlord_id FK, name, type, memo / `UNIQUE(property_id, name)`

**tenant** — id, landlord_id FK, name_enc, phone_enc, phone_hash(검색·중복용 HMAC), memo, notify_opt_out(boolean)

**lease**
| 컬럼 | 타입 | 비고 |
|---|---|---|
| id | bigint PK | |
| landlord_id | bigint FK | |
| unit_id | bigint FK | |
| tenant_id | bigint FK | |
| deposit | bigint | |
| monthly_rent | bigint | 현재 월세 |
| maintenance_fee | bigint | 현재 고정 관리비 |
| due_day | smallint | 1~31 |
| start_date | date | |
| end_date | date | |
| status | varchar | ACTIVE / ENDED |
| issue_days_before | smallint | 기본 7 |
| bank_name | varchar | |
| account_no_enc | varchar | 암호화 |
| account_holder | varchar | |
| memo | text | |
- 제약: `EXCLUDE USING gist (unit_id WITH =, daterange(start_date, end_date, '[]') WITH &&) WHERE (status='ACTIVE')` — 겹치는 활성 계약 차단 (btree_gist 확장 필요)

**lease_rent_history** — id, lease_id FK, monthly_rent, maintenance_fee, effective_from(date, 청구월 기준), reason

**charge**
| 컬럼 | 타입 | 비고 |
|---|---|---|
| id | bigint PK | |
| landlord_id | bigint FK | |
| lease_id | bigint FK | |
| billing_month | date | 매월 1일로 정규화 |
| due_date | date | |
| total_amount | bigint | 항목 합계 |
| paid_amount | bigint | 입금 합계 |
| status | varchar | 4.4.2 참조 |
| issued_at | timestamptz nullable | |
| paid_at | timestamptz nullable | |
| version | bigint | 낙관적 락 |
- 제약: `UNIQUE(lease_id, billing_month)`, `CHECK(paid_amount >= 0)`
- 인덱스: `(landlord_id, billing_month, status)`, `(status, due_date)`

**charge_item** — id, charge_id FK, type(RENT/MAINTENANCE/ELECTRIC/WATER/GAS/OTHER), label, amount, memo

**payment_record** — id, charge_id FK, landlord_id, amount, paid_on(date), source(MANUAL/TENANT_REPORT), memo, created_by, deleted_at(소프트 삭제)

**charge_status_history** — id, charge_id, from_status, to_status, actor_type(LANDLORD/TENANT/SYSTEM), actor_id, reason, created_at

**tenant_access_token** — id, lease_id FK, token_hash UNIQUE, expires_at, revoked_at, last_used_at

**notification**
| 컬럼 | 타입 | 비고 |
|---|---|---|
| id | bigint PK | |
| landlord_id | bigint | |
| recipient_type | varchar | LANDLORD / TENANT |
| recipient_ref | bigint | |
| channel | varchar | KAKAO_ALIMTALK / SMS / EMAIL / LOG |
| type | varchar | CHARGE_ISSUED / REMINDER / OVERDUE_D1 / ... / LEASE_EXPIRY_D90 |
| charge_id | bigint nullable | |
| lease_id | bigint nullable | |
| dedupe_key | varchar UNIQUE | |
| payload | jsonb | 템플릿 변수 |
| status | varchar | PENDING / SENT / FAILED / SKIPPED |
| attempt_count | smallint | |
| next_attempt_at | timestamptz | |
| provider_message_id | varchar nullable | |
| last_error | text nullable | |
| sent_at | timestamptz nullable | |
- 인덱스: `(status, next_attempt_at)`

**attachment** — id, landlord_id, owner_type, owner_id, storage_key, original_filename, content_type, size_bytes

**audit_log** — id, landlord_id, actor, action, target_type, target_id, detail(jsonb), ip, created_at

**consent_log** — id, landlord_id, document_type, document_version, agreed_at

**shedlock** — ShedLock 표준 테이블

### 5.2 개인정보 암호화
- 암호화 대상: 세입자 이름, 연락처, 계좌번호, 임대인 연락처
- 방식: **AES-256-GCM**, 애플리케이션 레벨 `AttributeConverter`로 투명 처리
- 키: 환경변수/시크릿 매니저에서 주입, **키 버전 필드**를 암호문에 포함(키 교체 대비)
- 검색이 필요한 필드(전화번호 중복 확인)는 **HMAC-SHA256 해시**를 별도 컬럼으로 저장
- 로그 출력 시 이름·연락처·계좌는 마스킹 (예: `010-****-1234`)

---

## 6. 화면 설계

### 6.1 임대인 화면 (모바일 우선, 반응형)

| 경로 | 화면 | 핵심 요소 |
|---|---|---|
| `/` | 랜딩 | 서비스 소개, 가입 버튼 |
| `/login`, `/signup` | 인증 | 이메일/카카오 |
| `/app/dashboard` | 대시보드 | 이번 달 요약 카드(받을 돈/받은 돈/미납), 미납자 목록, 만료 임박 |
| `/app/properties` | 건물 목록 | 건물별 호실 수·공실 수 |
| `/app/properties/{id}` | 건물 상세 | 호실 목록, 호실 추가 |
| `/app/units/{id}` | 호실 상세 | 현재 계약, 최근 청구 이력 |
| `/app/leases/new` | 계약 등록 | 단계형 폼(세입자 → 금액 → 기간 → 계좌) |
| `/app/leases/{id}` | 계약 상세/수정 | 세입자 링크 복사/재발급, 월세 변경 이력 |
| `/app/charges` | 월별 청구 목록 | 월 선택, 상태 필터, 일괄 입금확인 |
| `/app/charges/{id}` | 청구 상세 | 항목, 입금 기록, 발송 이력, 상태 이력 |
| `/app/notifications` | 알림 이력 | 발송 성공/실패 |
| `/app/reports` | 리포트 | 연간 수입, 엑셀 다운로드 |
| `/app/settings` | 설정 | 프로필, 알림 설정, 탈퇴 |

### 6.2 세입자 화면
| 경로 | 화면 |
|---|---|
| `/t/{token}` | 이번 달 청구서 + 입금 안내 + "입금했어요" |
| `/t/{token}/history` | 납부 이력 |
| `/t/{token}/optout` | 알림 수신 거부 |

### 6.3 UI/UX 원칙
- **가독성**: 기본 글자 16px 이상, 주요 버튼 높이 48px 이상, 대비 높은 색상
- **용어**: 전문 용어 최소화 ("청구" 대신 "이번 달 받을 월세")
- **상태 색상**: 입금완료=초록, 대기=회색, 미납=빨강 (색만으로 구분하지 않고 텍스트 병기)
- **위험 작업 확인**: 삭제·취소·계약 종료는 확인 모달
- **빈 상태 화면**: 건물이 없을 때 "첫 건물을 등록해 보세요" 안내
- **HTMX 사용 지점**: 입금 확인 토글, 청구 목록 필터, 폼 부분 검증 (전체 새로고침 최소화)

---

## 7. 라우팅 / API 설계 개요

SSR 중심이므로 HTML 라우트가 주이지만, **서비스 계층은 REST로 전환 가능**하도록 컨트롤러와 서비스를 분리한다.

### 7.1 임대인 영역 (인증 필요, `ROLE_LANDLORD`)

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET/POST | `/app/properties` | 건물 목록/등록 |
| GET/POST/PUT/DELETE | `/app/properties/{id}` | 건물 상세/수정/삭제 |
| POST | `/app/properties/{id}/units` | 호실 등록 |
| POST | `/app/leases` | 계약 등록 |
| POST | `/app/leases/{id}/end` | 계약 종료 |
| POST | `/app/leases/{id}/rent-change` | 월세 변경(적용월 지정) |
| POST | `/app/leases/{id}/tenant-link/reissue` | 세입자 링크 재발급 |
| GET | `/app/charges?month=2026-11&status=OVERDUE` | 청구 목록 |
| POST | `/app/charges/{id}/payments` | 입금 기록 추가 |
| POST | `/app/charges/{id}/mark-paid` | 전액 입금 처리 |
| POST | `/app/charges/{id}/cancel` | 청구 취소 |
| POST | `/app/charges/{id}/resend` | 청구서 재발송 |
| GET | `/app/reports/annual.xlsx?year=2026` | 연간 리포트 |

### 7.2 세입자 영역 (토큰 인증)
| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/t/{token}` | 청구서 보기 |
| POST | `/t/{token}/charges/{chargeId}/report-paid` | 입금했어요 (멱등) |
| POST | `/t/{token}/optout` | 수신 거부 |

### 7.3 시스템/운영
| 경로 | 설명 |
|---|---|
| `/actuator/health` | 헬스체크 (외부 비공개 또는 인증) |
| 내부 관리자 `/admin/**` | 알림 실패 재처리, 사용자 조회 (`ROLE_ADMIN`, IP 제한) |

### 7.4 공통 규칙
- 모든 임대인 영역 조회·수정은 **소유권 검증 필수** (`landlord_id` 일치). 다른 사람의 리소스 ID를 넣어도 404를 반환 (존재 여부 노출 금지)
- POST 폼은 CSRF 토큰 필수
- 에러 응답은 화면용 메시지와 내부 로그를 분리

---

## 8. 비기능 요구사항

### 8.1 보안 체크리스트
- [ ] 비밀번호 BCrypt (strength 10 이상)
- [ ] 세션 쿠키 `HttpOnly`, `Secure`, `SameSite=Lax`
- [ ] CSRF 활성화
- [ ] 보안 헤더: CSP, X-Content-Type-Options, X-Frame-Options, Referrer-Policy, HSTS
- [ ] 모든 리소스 접근에 소유권 검증 (IDOR 방지), **IDOR 테스트 필수**
- [ ] SQL 인젝션 방지(파라미터 바인딩), XSS 방지(Thymeleaf 이스케이프 기본, `th:utext` 금지)
- [ ] 파일 업로드: 확장자/MIME/크기 검증, 저장 파일명 랜덤화, 직접 실행 불가 스토리지
- [ ] 레이트 리미트: 로그인, 세입자 링크, 비밀번호 재설정
- [ ] 의존성 취약점 점검 (Dependabot)
- [ ] 비밀값은 환경변수/시크릿 매니저, `.env`는 git 제외
- [ ] 관리자 기능은 별도 권한 + IP 제한

### 8.2 개인정보 보호 (법률 자문이 아님, 출시 전 확인 필요)
- 개인정보보호법 준수: **수집 최소화**(주민등록번호 등 고유식별정보 수집 금지), 목적 외 이용 금지
- **처리방침 공개**, 수집·이용 동의 기록
- 임대인이 세입자 정보를 입력하는 구조이므로, 서비스는 **수탁자(처리 위탁) 지위**가 될 수 있음. 약관/방침에 구조를 명시하고 출시 전 전문가 검토 권장
- 보관/파기: 탈퇴 후 30일 내 파기, 계약 종료 후 보관 기간 정책 확정(미정 → 개발자 확인 필요)
- 외부 위탁사(알림 대행사, 클라우드)는 처리방침에 명시
- 접근 기록 보관 (`audit_log`)

### 8.3 성능/용량 목표 (MVP)
- 임대인 1,000명, 호실 20,000개, 월 청구 20,000건 규모에서 동작
- 대시보드 응답 p95 < 500ms
- 배치(청구 생성) 20,000건 < 5분
- 가용성: 월 99% 목표 (1인 운영 수준)

### 8.4 관측성
- 구조 로그 + 요청 ID(MDC)
- 배치/발송 워커 결과 로그(성공/실패/스킵 건수)
- 알림 `FAILED` 누적 시 개발자에게 이메일/슬랙 통보
- Sentry로 예외 수집, 개인정보 필터링

### 8.5 접근성/호환
- 모바일 Safari(iOS 15+), Chrome, 삼성 인터넷
- 화면 확대 허용, 시스템 폰트 크기 존중

---

## 9. 개발 계획 (마일스톤)

> 일정은 개발자가 주 10~15시간 투입하는 경우의 목표이며, 본업·이직 준비와 병행한다. **밀리면 범위를 줄이고 기간은 늘리지 않는다.**

### M0. 프로젝트 세팅 (3~4일)
- Gradle 프로젝트, 패키지 구조, Docker Compose(Postgres), Flyway 초기 마이그레이션
- Spotless, GitHub Actions(빌드+테스트), Testcontainers 환경
- ADR-001(SSR 선택), ADR-002(시간대), ADR-003(금액 타입)
- **완료 조건**: `./gradlew build` 성공, 로컬에서 앱 기동, CI 통과

### M1. 인증과 기본 골격 (1주)
- AUTH-1~7, 레이아웃(Tailwind), 에러 페이지, 약관·처리방침 정적 페이지
- 카카오 OAuth2 연동, 비밀번호 재설정(메일은 로그 출력으로 대체 가능)
- **완료 조건**: 가입→로그인→로그아웃, 타 계정 접근 테스트 통과

### M2. 건물·호실·계약 (1주)
- PROP-1~3, LEASE-1~4, 암호화 컨버터, 월세 변경 이력
- 겹치는 계약 차단(DB 제약 + 서비스 검증 테스트)
- **완료 조건**: 건물→호실→계약 등록 플로우, 소유권 검증(IDOR) 테스트

### M3. 청구 생성과 상태 모델 (1~1.5주) ← 가장 중요
- CHG-1~3, 청구 상태 전이, 상태 이력, 배치(ShedLock 포함)
- PAY-1~4, 낙관적 락, 입금 기록
- **테스트 필수**: 배치 재실행 멱등성, 말일/2월 납부일 보정, 상태 전이 전수 테스트, 동시 입금 처리, 월세 변경 적용월
- **완료 조건**: 배치를 두 번 돌려도 청구 중복 없음, 동시 요청 테스트 통과

### M4. 세입자 링크와 대시보드 (1주)
- TEN-1~4, 6, 토큰 발급/폐기/해시 저장, 레이트 리미트
- DASH-1~4
- **완료 조건**: 토큰 위조·만료·폐기 테스트 통과, 세입자가 타 계약 데이터를 볼 수 없음

### M5. 알림 시스템 (1~1.5주)
- Outbox 기반 발송 큐, `NotificationSender` 인터페이스, `LogNotificationSender`, 이메일 발송
- 알림 시나리오(청구/리마인드/미납/계약 만료) 스케줄링, 중복 방지, 재시도, 허용 시간대
- 알림톡/SMS 대행사 연동은 **사업자등록·채널 심사가 끝난 뒤** 같은 인터페이스 구현체로 추가
- **완료 조건**: 같은 알림이 2번 발송되지 않음(동시 워커 테스트), 실패 재시도/최종 실패 처리

### M6. 출시 준비 (1주)
- 배포(VM + Docker Compose + Caddy), 백업/복구 리허설, Sentry, UptimeRobot
- 보안 체크리스트 점검, 개인정보 파기 배치, PWA 기본, 문의 폼
- 시드 데이터/데모 계정, 간단한 사용 안내 페이지
- **완료 조건**: 운영 환경에서 전체 플로우 수동 점검 + 백업 복구 성공

### Phase 2 (출시 후)
PROP-4, LEASE-5~7, CHG-4, PAY-5, NOTI-6/8, DASH-5~7, SET-4/5, 일할 계산, 계약 갱신 플로우

### Phase 3 (유료화)
구독 결제(PG), 플랜별 호실 제한, 사업자 정보, 세금계산서/영수증 처리, 자동 입금확인 검토

---

## 10. 프로젝트 구조

도메인 단위 모듈러 모놀리스 (패키지 간 의존 방향을 `ArchUnit` 등으로 검증 권장)

```
rentkeeper/
├─ CLAUDE.md
├─ docs/
│  ├─ PRD.md
│  ├─ decisions/            # ADR-001-...md
│  ├─ notification-templates.md
│  └─ runbook.md            # 운영 절차(배포, 복구, 장애 대응)
├─ docker/
│  └─ docker-compose.yml
├─ src/main/java/com/rentkeeper/
│  ├─ RentKeeperApplication.java
│  ├─ common/               # 예외, 응답, 시간/금액 유틸, 암호화, 로깅
│  ├─ config/               # Security, Scheduling, Web, Storage 설정
│  ├─ auth/                 # 가입, 로그인, OAuth, 비밀번호 재설정
│  ├─ landlord/
│  ├─ property/             # 건물, 호실
│  ├─ lease/                # 계약, 세입자, 월세 이력
│  ├─ billing/              # 청구, 항목, 입금, 상태 모델, 배치
│  ├─ notification/         # outbox, sender 인터페이스, 구현체, 스케줄러
│  ├─ tenantportal/         # 세입자 링크 컨트롤러, 토큰
│  ├─ dashboard/
│  ├─ report/
│  └─ admin/
├─ src/main/resources/
│  ├─ templates/            # Thymeleaf
│  ├─ static/               # CSS, JS, PWA manifest
│  ├─ db/migration/         # V1__init.sql ...
│  └─ application-*.yml
└─ src/test/java/...
```

**계층 규칙**
- `controller → service → repository` 단방향. 컨트롤러에 비즈니스 로직 금지
- 엔티티를 화면에 직접 노출하지 않고 DTO/ViewModel 사용
- 도메인 규칙(상태 전이, 금액 계산)은 **엔티티/도메인 서비스 안에** 둔다 (빈약한 도메인 모델 지양)
- 모듈 간 참조는 서비스 인터페이스 또는 이벤트를 통해서만

---

## 11. 코딩 컨벤션

- Java 21 기능(record, switch 패턴 등) 적절히 사용
- 네이밍: 클래스 `PascalCase`, 메서드·변수 `camelCase`, DB 컬럼 `snake_case`
- 엔티티: 기본 생성자 `protected`, setter 지양, 상태 변경은 의미 있는 메서드(`charge.markPaid(...)`)로
- 예외: 도메인 예외(`BusinessException` 계열)와 시스템 예외 구분, 에러 코드 enum
- 시간: `java.time` + `Clock` 주입 (**`LocalDate.now()` 직접 호출 금지**, 테스트에서 시간 고정을 위해)
- 트랜잭션: 서비스 계층에서 `@Transactional`, 읽기 전용은 `readOnly = true`
- N+1 방지: 목록 조회는 fetch join/QueryDSL 프로젝션, 의심되는 곳은 쿼리 수 테스트
- 주석: "무엇"이 아니라 "왜"를 설명
- 커밋: Conventional Commits (`feat:`, `fix:`, `test:`, `docs:`, `refactor:`)
- 브랜치: `main` 보호, 기능별 브랜치 → PR → CI 통과 후 머지 (혼자라도 PR 사용)

---

## 12. 테스트 전략

| 종류 | 대상 | 도구 |
|---|---|---|
| 단위 | 상태 전이, 금액·납부일 계산, 토큰 생성, 암호화 | JUnit5, AssertJ |
| 통합 | Repository, 서비스, 배치, Outbox 워커 | `@SpringBootTest`, Testcontainers |
| 웹 | 컨트롤러, 보안, CSRF, 권한 | MockMvc, Spring Security Test |
| 동시성 | 동시 입금 처리, 동시 알림 발송, 배치 중복 | `ExecutorService` + CountDownLatch |
| 보안 | IDOR(타인 리소스 접근), 토큰 위변조/만료 | MockMvc |
| E2E(선택) | 핵심 플로우 1~2개 | Playwright (Phase 2) |

**반드시 있어야 할 테스트 케이스 (최소)**
1. 같은 달 청구 생성 배치를 2번/동시에 실행해도 청구가 1건
2. 납부일 31일 계약이 2월·4월에 말일로 보정
3. 월세 변경 후 과거 청구 금액은 그대로, 적용월부터 새 금액
4. `PAID` 청구는 취소 불가, 허용되지 않은 상태 전이는 예외
5. 부분 입금 합계가 총액 이상이면 자동 `PAID`
6. 동일 알림(dedupe_key)이 두 번 생성·발송되지 않음
7. 임대인 A가 임대인 B의 건물/계약/청구 ID로 접근 시 404
8. 폐기·만료된 세입자 토큰 접근 거부, 다른 계약의 청구 ID 접근 거부
9. 활성 계약 기간 겹침 등록 차단
10. 세입자 "입금했어요" 연타 시 상태·알림이 한 번만 반영

**커버리지**: 수치 목표보다 **도메인(billing, lease, notification) 핵심 로직 우선**. 단순 getter/CRUD 테스트는 지양.

---

## 13. 설정 및 환경변수

| 변수 | 설명 |
|---|---|
| `SPRING_PROFILES_ACTIVE` | local / prod |
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | PostgreSQL |
| `APP_ENCRYPTION_KEY`, `APP_ENCRYPTION_KEY_VERSION` | AES-256 키 (Base64) |
| `APP_HMAC_KEY` | 전화번호 해시용 |
| `KAKAO_CLIENT_ID`, `KAKAO_CLIENT_SECRET` | 카카오 로그인 |
| `STORAGE_ENDPOINT`, `STORAGE_BUCKET`, `STORAGE_ACCESS_KEY`, `STORAGE_SECRET_KEY` | S3 호환 스토리지 |
| `NOTIFICATION_PROVIDER` | log / solapi 등 |
| `NOTIFICATION_API_KEY`, `NOTIFICATION_API_SECRET`, `NOTIFICATION_SENDER_PHONE` | 대행사 |
| `MAIL_HOST`, `MAIL_USER`, `MAIL_PASSWORD` | 이메일 |
| `SENTRY_DSN` | 에러 수집 |
| `APP_BASE_URL` | 링크 생성용 (예: https://example.com) |

- `.env.example`만 커밋, 실제 `.env`는 gitignore
- 설정값은 `@ConfigurationProperties`로 타입 안전하게 바인딩

---

## 14. 운영 정책

### 14.1 배포
- `main` 머지 → GitHub Actions: 테스트 → 이미지 빌드 → 서버에 SSH 배포 → 헬스체크 → 실패 시 이전 이미지로 롤백
- DB 마이그레이션은 **하위 호환** 방식으로 작성 (컬럼 삭제는 2단계 배포)

### 14.2 백업/복구
- 일 1회 `pg_dump`, 7일 보관, 월 1회 복구 리허설
- 복구 절차를 `docs/runbook.md`에 문서화

### 14.3 장애 대응
- 알림 발송 실패율 급증, 배치 실패, 서버 다운 시 개발자에게 즉시 통지
- 알림 대행사 장애 대비: 발송 실패 건은 `FAILED`로 남기고 관리자 화면에서 재처리 가능

### 14.4 법·규정 사전 점검 (출시 전, 개발자가 직접 확인)
- 사업자등록 필요 시점 (구독 결제, 알림톡 채널 개설)
- 개인정보처리방침·이용약관 작성과 위탁 관계 정리
- 알림톡 템플릿 심사 규정
- 통신판매업 신고 필요 여부 (유료화 시)

---

## 15. 리스크와 대응

| 리스크 | 영향 | 대응 |
|---|---|---|
| 알림톡 연동 지연(사업자·심사) | 핵심 가치 제공 지연 | 이메일/SMS로 선출시, 인터페이스 추상화 유지 |
| 이중 청구/중복 발송 | 신뢰도 치명적 손상 | UNIQUE 제약 + 멱등성 + 테스트 필수 |
| 개인정보 유출 | 법적·신뢰 문제 | 암호화, 최소 수집, 접근 로그, 로그 마스킹 |
| 사용자 확보 실패 | 서비스 중단 | 3개월 시점 판단 기준(아래) 적용, 포트폴리오로 전환 |
| 개발 지연으로 이직 준비 잠식 | 커리어 계획 차질 | 범위 축소 우선, 주당 투입 시간 상한 |
| 경쟁 서비스 | 차별화 부족 | 임대인 UX 단순화, 저렴한 가격, 무료 구간 넓게 |
| 1인 운영 장애 | 서비스 불안정 | 자동 헬스체크, 단순 아키텍처 유지 |

---

## 16. 성공 지표와 중단 기준

**출시 후 3개월 시점 판단**
| 지표 | 목표 | 판단 |
|---|---|---|
| 가입 임대인 | 30명 이상 | 미달 시 홍보 방법 재점검 |
| 활성 임대인 (월 1회 이상 사용) | 15명 이상 | 미달 시 제품 문제 가능성 |
| 5호실 이상 임대인 비율 | 30% 이상 | 유료 전환 가능성 지표 |
| 유료 전환 의사 인터뷰 | 5명 이상 | |
| 알림 발송 성공률 | 98% 이상 | 기술 지표 |

- **3개월 후 활성 임대인이 5명 미만**이면 서비스 확장을 중단하고 **포트폴리오로 정리**한다. (README, 아키텍처 문서, 부하 테스트 결과, ADR 정리)
- 이직 일정은 서비스 성과와 무관하게 별도로 유지한다.

---

## 17. 포트폴리오 정리 가이드 (개발과 병행해서 기록)

README와 `docs/`에 아래를 남긴다. 면접 답변 원고가 된다.
1. 문제 정의와 타깃, 왜 이 서비스를 만들었는가
2. 아키텍처 다이어그램, ERD, 청구 상태 다이어그램
3. 설계 의사결정(ADR): SSR 선택, 멀티테넌시 방식(컬럼 기반), 낙관적 락 선택, Outbox 패턴, 토큰 해시 저장, 시간 처리
4. **문제 해결 사례**: 중복 청구 방지, 동시 입금 충돌, 알림 중복 발송 방지, 말일 보정 (재현 → 원인 → 해결 → 테스트)
5. 성능 측정(배치 처리 시간, 대시보드 응답), 개선 전후 수치
6. 보안 대응(IDOR 테스트, 암호화, 레이트 리미트)
7. 운영 경험(배포, 백업·복구, 장애 사례, 사용자 수·피드백)

---

## 18. 폐지됨(서비스 운영 목적)

---

## 19. 미정 사항 (개발 전 개발자와 확정할 것)

1. 서비스 이름 및 도메인
2. 청구 생성 기본 시점(납부일 며칠 전)과 알림 시간대 정책
3. 시작월/종료월 일할 계산 정책 (MVP는 일할 없음으로 가정, 확정 필요)
4. 무료/유료 플랜 구조(호실 수 기준 기본안: 3호실 이하 무료)
5. 세입자 정보 보관 기간(계약 종료 후 N년)
6. 알림톡 대행사 선정과 사업자등록 시점
7. 배포 서버 선택(Lightsail vs EC2 vs PaaS)
8. 카카오 로그인 시 이메일 미제공 계정의 알림 수신 경로

---

## 부록 A. CLAUDE.md 초안 (프로젝트 루트에 별도 저장)

```markdown
# RentKeeper

소규모 임대인용 월세 관리 웹 서비스. 상세 기획은 docs/PRD.md 참조.

## 스택
Java 21, Spring Boot 3, Gradle(Kotlin DSL), JPA+QueryDSL, PostgreSQL 16, Flyway,
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
- 모르는 요구사항은 추측하지 말고 질문.
```

## 부록 B. Claude Code 첫 지시 프롬프트 예시

```
docs/PRD.md와 CLAUDE.md를 읽고 요약해 줘. 이해한 내용과 모호한 부분을 먼저 알려 줘.
그다음 M0(프로젝트 세팅)의 작업 계획을 단계별로 제시해 줘. 내가 승인하면 진행하자.
코드를 짜기 전에 각 설계 결정의 이유를 설명해 주고, ADR로 남겨 줘.
```

## 부록 C. 마일스톤 시작 시 프롬프트 템플릿

```
M{N}을 시작하자. PRD의 해당 마일스톤과 관련 기능(ID) 요구사항을 다시 읽고,
1) 구현 계획(파일/클래스 목록, DB 변경점), 2) 작성할 테스트 목록,
3) 내가 결정해야 할 사항을 먼저 정리해 줘. 승인 전에는 코드를 쓰지 마.
```
