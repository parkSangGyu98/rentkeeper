-- 이메일 인증 여부. NULL이면 미인증 (M1-b). 알림은 인증된 주소로만 보낸다
ALTER TABLE landlord ADD COLUMN email_verified_at timestamptz;

-- 이메일 인증·이메일 변경·비밀번호 재설정에 쓰는 1회용 토큰 (ADR-011).
-- 토큰 원문은 메일 링크에만 있고, DB에는 SHA-256 해시만 저장한다.
CREATE TABLE account_token (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    landlord_id  bigint       NOT NULL REFERENCES landlord (id) ON DELETE CASCADE,
    purpose      varchar(30)  NOT NULL,
    -- 토큰 원문의 SHA-256 (소문자 hex 64자)
    token_hash   char(64)     NOT NULL,
    -- 인증할 이메일 주소. 이메일 인증·이메일 변경 토큰에만 있다
    target_email varchar(254),
    expires_at   timestamptz  NOT NULL,
    -- 사용했거나 새 토큰 발급으로 무효가 된 시각. NULL이면 아직 쓸 수 있다 (1회용)
    used_at      timestamptz,
    created_at   timestamptz  NOT NULL,
    updated_at   timestamptz  NOT NULL,
    CONSTRAINT uk_account_token_hash UNIQUE (token_hash),
    CONSTRAINT ck_account_token_purpose CHECK (purpose IN ('EMAIL_VERIFICATION', 'EMAIL_CHANGE', 'PASSWORD_RESET')),
    CONSTRAINT ck_account_token_hash_hex CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    -- 이메일 인증·변경 토큰은 대상 주소가 반드시 있고, 재설정 토큰은 없어야 한다
    CONSTRAINT ck_account_token_target_email
        CHECK ((purpose IN ('EMAIL_VERIFICATION', 'EMAIL_CHANGE')) = (target_email IS NOT NULL)),
    CONSTRAINT ck_account_token_email_normalized CHECK (target_email = lower(btrim(target_email))),
    CONSTRAINT ck_account_token_expiry CHECK (expires_at > created_at)
);

-- 같은 계정·용도의 아직 쓸 수 있는 토큰을 찾을 때 (새 토큰 발급 시 이전 토큰 무효화)
CREATE INDEX ix_account_token_active ON account_token (landlord_id, purpose) WHERE used_at IS NULL;
-- 만료 토큰 정리 작업용
CREATE INDEX ix_account_token_expires_at ON account_token (expires_at);
