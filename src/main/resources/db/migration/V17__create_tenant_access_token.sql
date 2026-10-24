-- 세입자 링크 토큰 (M4, PRD 4.7, ADR-026). 세입자는 가입 없이 이 링크로만 자기 계약의 받을 월세를 본다.
-- 토큰 원문은 링크를 만든 그 화면에만 보이고, DB에는 SHA-256 해시만 저장한다(원문을 다시 보여 줄 수 없다).
-- 만료일은 저장하지 않는다: 계약의 실제 끝 + 90일로 조회할 때 계산한다(계약 수정·종료 때 갱신할 필요가 없다).
CREATE TABLE tenant_access_token (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    landlord_id  bigint       NOT NULL,
    lease_id     bigint       NOT NULL,
    token_hash   char(64)     NOT NULL,
    revoked_at   timestamptz,
    last_used_at timestamptz,
    created_at   timestamptz  NOT NULL,
    updated_at   timestamptz  NOT NULL,
    CONSTRAINT fk_tenant_access_token_landlord FOREIGN KEY (landlord_id) REFERENCES landlord (id) ON DELETE CASCADE,
    -- 같은 임대인의 계약만 (복합 FK, ADR-018). 잘못 등록한 계약을 지우면 링크도 함께 지워진다
    CONSTRAINT fk_tenant_access_token_lease FOREIGN KEY (lease_id, landlord_id) REFERENCES lease (id, landlord_id)
        ON DELETE CASCADE,
    CONSTRAINT uk_tenant_access_token_hash UNIQUE (token_hash),
    CONSTRAINT ck_tenant_access_token_hash_hex CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_tenant_access_token_revoked CHECK (revoked_at IS NULL OR revoked_at >= created_at)
);

-- 계약당 열린 링크는 하나 (M4 결정 3). 새 링크를 만들면 예전 링크를 먼저 닫는다
CREATE UNIQUE INDEX ux_tenant_access_token_open ON tenant_access_token (lease_id) WHERE revoked_at IS NULL;
-- 계약의 링크 기록 (닫힌 것 포함, 계약 삭제 CASCADE)
CREATE INDEX ix_tenant_access_token_lease ON tenant_access_token (lease_id, landlord_id);
-- 임대인 파기(CASCADE) 때 링크 찾기
CREATE INDEX ix_tenant_access_token_landlord ON tenant_access_token (landlord_id);

COMMENT ON TABLE tenant_access_token IS '세입자 링크 토큰 (PRD 4.7, ADR-026). 세입자는 가입 없이 이 링크로 자기 계약의 받을 월세를 보고 "입금했어요"를 알린다. 원문은 링크를 만든 화면에만 보이고 저장하지 않는다. 계약을 지우거나 임대인을 파기하면 함께 지워진다.';
COMMENT ON COLUMN tenant_access_token.id IS '링크 ID. 세입자가 한 일(입금 신고)의 상태 이력 actor_id로도 쓴다.';
COMMENT ON COLUMN tenant_access_token.landlord_id IS '링크를 만든 임대인 (landlord.id). 계약의 임대인과 같아야 한다(복합 FK).';
COMMENT ON COLUMN tenant_access_token.lease_id IS '이 링크로 볼 수 있는 계약 (lease.id). 세입자 화면의 모든 조회는 이 계약으로만 한정한다.';
COMMENT ON COLUMN tenant_access_token.token_hash IS '토큰 원문의 SHA-256 (소문자 hex 64자). 원문은 32바이트 난수의 Base64URL(43자)이다. DB가 유출돼도 링크를 만들 수 없게 원문 대신 저장한다.';
COMMENT ON COLUMN tenant_access_token.revoked_at IS '링크를 닫은 시각 (UTC). NULL=열려 있음(계약의 실제 끝 + 90일까지 쓸 수 있다). 임대인이 [링크 닫기]를 누르거나 [새 링크 만들기]로 바꾸면 기록된다. 닫힌 링크는 다시 열지 않는다.';
COMMENT ON COLUMN tenant_access_token.last_used_at IS '세입자가 이 링크로 마지막으로 연 시각 (UTC). NULL=아직 열지 않음. 쓰기를 줄이려고 하루에 한 번만 갱신한다. 계약 상세에 "마지막으로 열어 본 날"로 보인다.';
COMMENT ON COLUMN tenant_access_token.created_at IS '링크를 만든 시각 (UTC, 앱의 Clock 기준).';
COMMENT ON COLUMN tenant_access_token.updated_at IS '마지막 수정 시각 (UTC, 앱의 Clock 기준).';

COMMENT ON CONSTRAINT uk_tenant_access_token_hash ON tenant_access_token IS '같은 토큰이 두 번 저장되지 않게 하고, 링크로 들어올 때 해시로 찾는다.';
COMMENT ON CONSTRAINT ck_tenant_access_token_hash_hex ON tenant_access_token IS '원문이 실수로 저장되지 않게 SHA-256 hex 64자만 받는다.';
COMMENT ON CONSTRAINT ck_tenant_access_token_revoked ON tenant_access_token IS '닫은 시각은 만든 시각보다 앞설 수 없다.';
COMMENT ON INDEX ux_tenant_access_token_open IS '계약당 열린 링크는 하나 (M4 결정 3). 동시에 새 링크를 두 번 만들어도 하나만 남는다.';
COMMENT ON INDEX ix_tenant_access_token_lease IS '계약의 링크 기록 찾기, 계약 삭제(CASCADE).';
COMMENT ON INDEX ix_tenant_access_token_landlord IS '임대인 파기(CASCADE) 때 링크 찾기.';

-- 세입자가 한 일의 actor_id 뜻을 덧붙인다 (V16 파일은 고치지 않는다, M4 결정 12)
COMMENT ON COLUMN charge_status_history.actor_id IS '바꾼 사람 ID. LANDLORD면 landlord.id, TENANT면 세입자가 쓴 링크의 tenant_access_token.id(링크가 지워지면 남은 숫자만 남는다), SYSTEM이면 NULL.';
