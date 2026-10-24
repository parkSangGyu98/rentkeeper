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
