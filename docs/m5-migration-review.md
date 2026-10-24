# M5 마이그레이션 검토 (V19~V21)

> 개발자 지시(2026-10-24): V19~V21은 확인을 기다리지 않고 커밋하고, 이 문서에 SQL 전문과 각 마이그레이션이 지키는 조건을 모은다.
> 검토해서 문제가 있으면 **새 마이그레이션으로** 고친다. 이미 커밋한 마이그레이션 파일은 고치지 않는다(Flyway 체크섬).
> V18은 커밋 전에 개발자가 확인했다(커밋 `8a87789`).

체크 표시는 작성자(Claude)가 파일과 테스트로 확인한 것이다. 개발자 검토 결과는 각 절 끝의 "검토" 줄에 적는다.

## V19 `notification` (알림 Outbox, 커밋 2)

- [x] 전화번호·메일 주소 컬럼이 없다 (받는 사람은 `recipient_type` + `tenant_id`/`landlord_id`로만 가리킨다)
- [x] 알림 본문·제목·링크 원문 컬럼이 없다 (보낼 때 만들고 저장하지 않는다)
- [x] 세입자 알림은 세입자·계약(·청구) ID로만 가리키고, 같은 임대인의 것만 가리키게 복합 FK(`(tenant_id, landlord_id)`, `(lease_id, landlord_id)`, `(charge_id, landlord_id)`)를 건다
- [x] `dedupe_key` UNIQUE (`uk_notification_dedupe_key`), 넣을 때 `ON CONFLICT DO NOTHING` (알림 멱등)
- [x] `dedupe_key` 형식에 개인정보가 없다 (종류·ID·날짜만, 테스트 `logsCarryOnlyIdsTypesAndResults`)
- [x] 오류는 예외 클래스 이름만 저장한다(`last_error`). 예외 메시지는 저장하지 않는다
- [x] 상태·사유·종류·채널은 CHECK로 정해진 값만 받는다. SKIPPED일 때만 사유, SENT일 때만 보낸 시각, SENDING일 때만 잠금 만료 시각
- [x] 받는 사람 종류마다 채울 칸·비울 칸·채널을 CHECK로 강제한다 (`ck_notification_recipient`)
- [x] 모든 컬럼·테이블에 한글 COMMENT, 목적이 분명하지 않은 제약·인덱스에도 COMMENT (`SchemaCommentsTests` 통과)
- [x] 시각은 timestamptz(UTC), `scheduled_for`는 서울 날짜라고 COMMENT에 적었다
- [x] 임대인 파기·계약 삭제·청구 삭제 때 함께 지워진다 (CASCADE). 운영 알림 행(임대인 없음)은 CASCADE 대상이 아니다 → 보관 정리 배치는 M6
- [x] 기존 테이블·기존 마이그레이션을 고치지 않는다 (새 테이블만)
- 검토:

```sql
-- 알림 Outbox (M5 결정 2·3·6, PRD 4.5 NOTI-1~5·7, ADR-028).
-- 업무 트랜잭션 안에서 PENDING 행을 넣고, 발송 워커가 30초마다 가져가 보낸다. 같은 알림은 dedupe_key로 한 번만 만든다.
-- 받는 사람은 ID로만 가리킨다: 전화번호·메일 주소·알림 본문·링크 원문은 저장하지 않는다(개발자 조건 1). 보낼 때 그때그때 만든다.
-- 시각은 앱 Clock 기준 UTC, scheduled_for는 서울 기준 날짜다(ADR-002).
CREATE TABLE notification (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    landlord_id     bigint,
    type            varchar(30)  NOT NULL,
    channel         varchar(20)  NOT NULL,
    recipient_type  varchar(10)  NOT NULL,
    tenant_id       bigint,
    lease_id        bigint,
    charge_id       bigint,
    ops_code        varchar(50),
    scheduled_for   date         NOT NULL,
    dedupe_key      varchar(200) NOT NULL,
    status          varchar(10)  NOT NULL,
    skip_reason     varchar(30),
    attempts        smallint     NOT NULL DEFAULT 0,
    next_attempt_at timestamptz  NOT NULL,
    claimed_until   timestamptz,
    last_error      varchar(200),
    sent_at         timestamptz,
    version         bigint       NOT NULL DEFAULT 0,
    created_at      timestamptz  NOT NULL,
    updated_at      timestamptz  NOT NULL,
    CONSTRAINT fk_notification_landlord FOREIGN KEY (landlord_id) REFERENCES landlord (id) ON DELETE CASCADE,
    -- 같은 임대인의 세입자·계약·청구만 (복합 FK, ADR-018). 잘못 등록한 계약을 지우면 그 알림 기록도 함께 지운다
    CONSTRAINT fk_notification_tenant FOREIGN KEY (tenant_id, landlord_id) REFERENCES tenant (id, landlord_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_notification_lease FOREIGN KEY (lease_id, landlord_id) REFERENCES lease (id, landlord_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_notification_charge FOREIGN KEY (charge_id, landlord_id) REFERENCES charge (id, landlord_id)
        ON DELETE CASCADE,
    -- 멱등 (CLAUDE.md, PRD 4.5): 같은 알림은 한 번만 만든다. 넣을 때 ON CONFLICT DO NOTHING
    CONSTRAINT uk_notification_dedupe_key UNIQUE (dedupe_key),
    CONSTRAINT ck_notification_type CHECK (type IN ('CHARGE_ISSUED', 'DUE_REMINDER', 'OVERDUE_NOTICE',
        'TENANT_REPORT_DIGEST', 'OVERDUE_DIGEST', 'LEASE_END_DIGEST', 'OPS_ALERT')),
    CONSTRAINT ck_notification_channel CHECK (channel IN ('TENANT_PHONE', 'EMAIL')),
    CONSTRAINT ck_notification_status CHECK (status IN ('PENDING', 'SENDING', 'SENT', 'FAILED', 'SKIPPED', 'EXPIRED')),
    CONSTRAINT ck_notification_skip_reason CHECK (
        (status = 'SKIPPED') = (skip_reason IS NOT NULL)
        AND (skip_reason IS NULL OR skip_reason IN ('CHANNEL_OFF', 'LANDLORD_OFF', 'OPTED_OUT',
            'LINK_CLOSED_BY_LANDLORD', 'LEASE_ENDED', 'STALE', 'DAILY_CAP', 'EMAIL_UNVERIFIED', 'NO_ADDRESS'))),
    -- 받는 사람 종류마다 필요한 칸이 정해져 있다
    CONSTRAINT ck_notification_recipient CHECK (
        (recipient_type = 'TENANT' AND channel = 'TENANT_PHONE' AND landlord_id IS NOT NULL
            AND tenant_id IS NOT NULL AND lease_id IS NOT NULL AND ops_code IS NULL)
        OR (recipient_type = 'LANDLORD' AND channel = 'EMAIL' AND landlord_id IS NOT NULL
            AND tenant_id IS NULL AND lease_id IS NULL AND charge_id IS NULL AND ops_code IS NULL)
        OR (recipient_type = 'OPS' AND channel = 'EMAIL' AND landlord_id IS NULL
            AND tenant_id IS NULL AND lease_id IS NULL AND charge_id IS NULL AND ops_code IS NOT NULL)),
    CONSTRAINT ck_notification_attempts CHECK (attempts >= 0),
    CONSTRAINT ck_notification_sent_at CHECK ((status = 'SENT') = (sent_at IS NOT NULL)),
    CONSTRAINT ck_notification_claimed CHECK ((status = 'SENDING') = (claimed_until IS NOT NULL)),
    CONSTRAINT ck_notification_version CHECK (version >= 0)
);

-- 발송 워커: 보낼 차례가 된 행 찾기 (기다림·보내는 중만, 끝난 행은 인덱스에 없다)
CREATE INDEX ix_notification_due ON notification (next_attempt_at) WHERE status IN ('PENDING', 'SENDING');
-- 알림 이력 화면(최근 90일)과 임대인 파기(CASCADE)
CREATE INDEX ix_notification_landlord_created ON notification (landlord_id, created_at);
-- 계약당 하루 1통 확인과 계약 삭제(CASCADE)
CREATE INDEX ix_notification_lease_day ON notification (lease_id, scheduled_for) WHERE lease_id IS NOT NULL;
-- 청구 삭제(CASCADE)
CREATE INDEX ix_notification_charge ON notification (charge_id) WHERE charge_id IS NOT NULL;
-- 세입자 삭제(CASCADE)
CREATE INDEX ix_notification_tenant ON notification (tenant_id) WHERE tenant_id IS NOT NULL;

COMMENT ON TABLE notification IS '알림 Outbox와 발송 이력 (PRD 4.5, ADR-028). 알림 하나가 한 행이다. 업무 처리와 같은 트랜잭션에서 PENDING으로 넣고 발송 워커가 보낸다. 전화번호·메일 주소·본문·링크 원문은 저장하지 않는다: 받는 사람은 ID로만 가리키고 본문은 보낼 때 만든다. 임대인을 파기하면 함께 지워진다.';
COMMENT ON COLUMN notification.id IS '알림 ID. 발송 로그와 (나중에) 문자·메일 대행사의 멱등 키로 쓴다.';
COMMENT ON COLUMN notification.landlord_id IS '이 알림이 속한 임대인 (landlord.id). 알림 이력 화면은 이 값으로 소유권을 확인한다. NULL=운영 알림(OPS, 임대인과 관계없음).';
COMMENT ON COLUMN notification.type IS '알림 종류. CHARGE_ISSUED=받을 월세 안내(세입자), DUE_REMINDER=납부일 전날 안내(세입자), OVERDUE_NOTICE=납부일 지남 안내 D+1·D+7(세입자), TENANT_REPORT_DIGEST=입금 신고 접수 요약(임대인, 시간당 1통), OVERDUE_DIGEST=납부일 지남 요약(임대인, 하루 1통), LEASE_END_DIGEST=곧 끝나는 계약 요약(임대인, 하루 1통), OPS_ALERT=운영 알림(개발자, 코드별 하루 1통).';
COMMENT ON COLUMN notification.channel IS '보내는 길. TENANT_PHONE=세입자 휴대폰(문자·알림톡, 아직 실제 발송기 없음, 설정 기본 꺼짐), EMAIL=메일(임대인은 인증한 주소로만, 운영은 OPS_ALERT_EMAIL).';
COMMENT ON COLUMN notification.recipient_type IS '받는 사람 종류. TENANT=세입자(tenant_id), LANDLORD=임대인 본인(landlord_id), OPS=운영자(OPS_ALERT_EMAIL 설정값).';
COMMENT ON COLUMN notification.tenant_id IS '받는 세입자 (tenant.id). TENANT일 때만. 전화번호는 보낼 때 이 행에서 복호화한다(저장하지 않음).';
COMMENT ON COLUMN notification.lease_id IS '세입자 알림의 계약 (lease.id). 계약당 하루 1통 확인과 알림 링크 만들기에 쓴다. TENANT일 때만.';
COMMENT ON COLUMN notification.charge_id IS '세입자 알림의 받을 월세 (charge.id). 보낼 때 상태를 다시 보고, 바뀌었으면 보내지 않는다(STALE). TENANT일 때만.';
COMMENT ON COLUMN notification.ops_code IS '운영 알림 코드 (예: CHARGE_BATCH_ALERT). OPS일 때만. 개인정보는 넣지 않는다.';
COMMENT ON COLUMN notification.scheduled_for IS '보내기로 한 날 (서울 날짜). 그날 20:00까지 보내지 못하면 EXPIRED로 하고 다음 날 보내지 않는다(날짜가 들어간 문구가 늦게 나가지 않게). 20:00 뒤에 만든 알림은 다음 날이다.';
COMMENT ON COLUMN notification.dedupe_key IS '같은 알림을 두 번 만들지 않기 위한 키. 예: CHARGE_ISSUED:{청구 ID}, OVERDUE_NOTICE:{청구 ID}:D7, OVERDUE_DIGEST:{임대인 ID}:{날짜}, OPS_ALERT:{코드}:{날짜}. 개인정보는 넣지 않는다.';
COMMENT ON COLUMN notification.status IS '발송 상태. PENDING=기다림, SENDING=워커가 가져가 보내는 중, SENT=보냄, FAILED=여러 번 실패해 그만둠, SKIPPED=보내지 않기로 함(skip_reason), EXPIRED=보낼 날이 지나 버림.';
COMMENT ON COLUMN notification.skip_reason IS '보내지 않은 이유 (SKIPPED일 때만). CHANNEL_OFF=세입자 안내 문자 기능이 꺼져 있음(서비스 설정), LANDLORD_OFF=임대인이 알림 설정에서 끔, OPTED_OUT=세입자가 안내 문자를 그만 받기로 함, LINK_CLOSED_BY_LANDLORD=임대인이 링크를 닫아 알림을 멈춤, LEASE_ENDED=계약이 끝나 링크를 쓸 수 없음, STALE=보낼 때 받을 월세 상태가 바뀜, DAILY_CAP=같은 계약에 오늘 이미 보냄, EMAIL_UNVERIFIED=임대인 이메일이 없거나 인증 전, NO_ADDRESS=운영 알림 주소(OPS_ALERT_EMAIL)가 없음.';
COMMENT ON COLUMN notification.attempts IS '보내 본 횟수. 워커가 가져갈 때마다 1 오른다. 3번 실패하면 FAILED.';
COMMENT ON COLUMN notification.next_attempt_at IS '이 시각부터 보낼 수 있다 (UTC). 발송 시간(09:00~20:00 서울) 밖이면 다음 09:00, 실패하면 1분·5분·15분 뒤.';
COMMENT ON COLUMN notification.claimed_until IS '워커가 가져간 행의 잠금 만료 시각 (UTC, SENDING일 때만). 워커가 죽어 이 시각이 지나면 다른 워커가 다시 가져간다(최소 1회 발송).';
COMMENT ON COLUMN notification.last_error IS '마지막 실패의 예외 클래스 이름. 예외 메시지는 개인정보·링크가 섞일 수 있어 남기지 않는다. NULL=실패 없음.';
COMMENT ON COLUMN notification.sent_at IS '보낸 시각 (UTC, SENT일 때만).';
COMMENT ON COLUMN notification.version IS '낙관적 락. 잠금이 만료돼 다른 워커가 다시 가져간 행을 앞 워커가 덮어쓰지 않게 한다.';
COMMENT ON COLUMN notification.created_at IS '알림을 만든 시각 (UTC, 앱의 Clock 기준).';
COMMENT ON COLUMN notification.updated_at IS '마지막 수정 시각 (UTC, 앱의 Clock 기준).';

COMMENT ON CONSTRAINT uk_notification_dedupe_key ON notification IS '같은 알림을 두 번 만들지 않는다 (알림 발송 멱등).';
COMMENT ON CONSTRAINT ck_notification_skip_reason ON notification IS 'SKIPPED일 때만 이유가 있고, 이유는 정해진 값만.';
COMMENT ON CONSTRAINT ck_notification_recipient ON notification IS '받는 사람 종류(TENANT·LANDLORD·OPS)마다 채워야 할 칸과 비워야 할 칸, 쓸 수 있는 채널.';
COMMENT ON CONSTRAINT ck_notification_sent_at ON notification IS 'SENT일 때만 보낸 시각이 있다.';
COMMENT ON CONSTRAINT ck_notification_claimed ON notification IS 'SENDING일 때만 잠금 만료 시각이 있다.';
COMMENT ON INDEX ix_notification_due IS '발송 워커가 보낼 차례가 된 기다림·보내는 중 행을 찾는다 (끝난 행은 빠진 부분 인덱스).';
COMMENT ON INDEX ix_notification_landlord_created IS '알림 이력 화면(최근 90일)과 임대인 파기(CASCADE).';
COMMENT ON INDEX ix_notification_lease_day IS '세입자 알림의 계약당 하루 1통 확인과 계약 삭제(CASCADE).';
COMMENT ON INDEX ix_notification_charge IS '받을 월세 삭제(CASCADE) 때 알림 찾기.';
COMMENT ON INDEX ix_notification_tenant IS '세입자 삭제(CASCADE) 때 알림 찾기.';
```

## V20 세입자 링크 유형·닫은 이유·계약의 알림 멈춤 (커밋 3)

- [x] 토큰 원문 컬럼을 만들지 않는다 (알림 링크도 `token_hash`만, 원문은 보낼 본문에만)
- [x] 기존 행 `purpose`는 모두 `LANDLORD` (M4까지 링크는 임대인만 만들었다)
- [x] 이미 닫힌 기존 행의 `revoke_reason`은 추정하지 않고 `UNKNOWN`으로 채운다 (개발자 변경 3). 앱은 `UNKNOWN`을 쓰지 않는다(`TenantAccessToken.revoke`가 거부)
- [x] 열린 링크는 `revoke_reason` NULL, 닫힌 링크는 값이 있다 (CHECK `ck_tenant_access_token_revoke_reason`)
- [x] 부분 UNIQUE `ux_tenant_access_token_open`은 V17을 고치지 않고 여기서 `DROP INDEX` 뒤 `(lease_id, purpose) WHERE revoked_at IS NULL`로 다시 만든다 (개발자 변경 3)
- [x] 계약의 알림 멈춤 플래그 `lease.tenant_notify_paused_at`을 이 파일에 넣었다 (개발자 변경 1, NULL=보냄)
- [x] 새 컬럼·제약·다시 만든 인덱스에 한글 COMMENT, `revoked_at` 설명은 닫은 이유 컬럼을 가리키게 갱신
- [ ] V20 적용 전 닫힌 행이 `UNKNOWN`이 되는지는 실제 기존 DB(로컬 DB, v19 → v20)에서 다음 bootRun 때 확인한다: `SELECT revoke_reason, count(*) FROM tenant_access_token WHERE revoked_at IS NOT NULL GROUP BY 1;` (자동 테스트는 빈 DB에서 시작해 기존 행이 없다)
- 검토:

```sql
-- 알림용 세입자 링크 유형, 링크를 닫은 이유, 계약의 세입자 알림 멈춤 (M5 결정 7·8, ADR-029).
-- 알림을 보낼 때마다 알림 전용 링크(NOTIFICATION)를 새로 만들고 예전 알림 링크는 닫는다. 임대인이 직접 만든 링크(LANDLORD)는 그대로 둔다.
-- 그래서 "계약당 열린 링크 1개"(M4 결정 3)를 "계약·유형마다 열린 링크 1개"로 바꾼다. V17은 고치지 않고 여기서 인덱스를 지우고 다시 만든다.

-- 기존 링크는 모두 임대인이 만든 것이다
ALTER TABLE tenant_access_token ADD COLUMN purpose varchar(20) NOT NULL DEFAULT 'LANDLORD';
ALTER TABLE tenant_access_token ADD COLUMN revoke_reason varchar(30);
-- 이미 닫힌 기존 행은 이유를 추정하지 않는다 (개발자 변경 3)
UPDATE tenant_access_token SET revoke_reason = 'UNKNOWN' WHERE revoked_at IS NOT NULL;

ALTER TABLE tenant_access_token ADD CONSTRAINT ck_tenant_access_token_purpose
    CHECK (purpose IN ('LANDLORD', 'NOTIFICATION'));
ALTER TABLE tenant_access_token ADD CONSTRAINT ck_tenant_access_token_revoke_reason CHECK (
    (revoked_at IS NULL) = (revoke_reason IS NULL)
    AND (revoke_reason IS NULL
        OR revoke_reason IN ('LANDLORD_CLOSED', 'LANDLORD_REISSUED', 'NOTIFICATION_ROTATED', 'UNKNOWN')));

DROP INDEX ux_tenant_access_token_open;
CREATE UNIQUE INDEX ux_tenant_access_token_open ON tenant_access_token (lease_id, purpose) WHERE revoked_at IS NULL;

-- 임대인이 [링크 닫기]를 누르면 그 계약의 알림 링크 발급을 멈춘다. [알림 다시 켜기]로만 다시 시작한다 (개발자 변경 1)
ALTER TABLE lease ADD COLUMN tenant_notify_paused_at timestamptz;

COMMENT ON COLUMN tenant_access_token.purpose IS '링크 유형. LANDLORD=임대인이 계약 상세에서 직접 만들어 보낸 링크, NOTIFICATION=알림(안내 문자)에 넣으려고 보낼 때 만든 링크. 계약·유형마다 열린 링크는 하나다. 세입자 화면에서는 두 유형이 똑같이 동작한다.';
COMMENT ON COLUMN tenant_access_token.revoke_reason IS '링크를 닫은 이유 (닫힌 링크만, 열린 링크는 NULL). LANDLORD_CLOSED=임대인이 [링크 닫기]를 누름, LANDLORD_REISSUED=임대인이 [새 링크 만들기]로 바꿈, NOTIFICATION_ROTATED=새 알림을 보내며 예전 알림 링크를 닫음, UNKNOWN=V20 전에 닫혀 이유가 기록되지 않음. 세입자 화면은 이유와 관계없이 같은 "링크를 열 수 없어요"다.';
COMMENT ON COLUMN tenant_access_token.revoked_at IS '링크를 닫은 시각 (UTC). NULL=열려 있음(계약의 실제 끝 + 90일까지 쓸 수 있다). 닫은 이유는 revoke_reason. 닫힌 링크는 다시 열지 않는다.';
COMMENT ON COLUMN lease.tenant_notify_paused_at IS '임대인이 [링크 닫기]로 이 계약의 세입자 알림을 멈춘 시각 (UTC). NULL=알림을 보냄. 값이 있으면 알림 링크를 만들지 않고 세입자 알림을 보내지 않는다(SKIPPED LINK_CLOSED_BY_LANDLORD). 임대인이 [알림 다시 켜기]를 눌러야 NULL로 돌아간다([새 링크 만들기]로는 돌아가지 않는다).';
COMMENT ON CONSTRAINT ck_tenant_access_token_purpose ON tenant_access_token IS '링크 유형은 LANDLORD 또는 NOTIFICATION.';
COMMENT ON CONSTRAINT ck_tenant_access_token_revoke_reason ON tenant_access_token IS '닫힌 링크에만 닫은 이유가 있고, 이유는 정해진 값만.';
COMMENT ON INDEX ux_tenant_access_token_open IS '계약·유형마다 열린 링크는 하나 (M5 결정 7, M4 결정 3을 바꿈). 동시에 새 링크를 두 번 만들어도 하나만 남는다.';
```
