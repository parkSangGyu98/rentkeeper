-- 임대인 계정 (PRD 5.1 landlord). M1-a 범위의 컬럼만 만든다.
-- 이후 단계에서 추가될 컬럼(email_verified_at, kakao_id, withdrawn_at, phone_enc)은 각 단계 마이그레이션에서 ADD COLUMN 한다.
CREATE TABLE landlord (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- 연락·알림용 이메일. LOCAL 계정은 로그인 ID 겸용. 앱에서 소문자로 정규화해 저장한다 (ADR-006)
    email         varchar(254),
    password_hash varchar(255),
    auth_provider varchar(20)  NOT NULL,
    name          varchar(50)  NOT NULL,
    status        varchar(20)  NOT NULL DEFAULT 'ACTIVE',
    plan          varchar(20)  NOT NULL DEFAULT 'FREE',
    -- 시각은 앱의 Clock이 채운다 (ADR-002). DB now()를 기본값으로 두지 않는다
    created_at    timestamptz  NOT NULL,
    updated_at    timestamptz  NOT NULL,
    CONSTRAINT ck_landlord_auth_provider CHECK (auth_provider IN ('LOCAL', 'KAKAO')),
    CONSTRAINT ck_landlord_status CHECK (status IN ('ACTIVE', 'WITHDRAWN')),
    CONSTRAINT ck_landlord_plan CHECK (plan IN ('FREE', 'PRO')),
    -- 정규화되지 않은 이메일(대문자, 앞뒤 공백)이 들어오는 것을 DB에서도 막는다. NULL은 통과한다
    CONSTRAINT ck_landlord_email_normalized CHECK (email = lower(btrim(email))),
    -- 이메일 가입 계정은 이메일과 비밀번호 해시가 반드시 있어야 한다
    CONSTRAINT ck_landlord_local_credentials
        CHECK (auth_provider <> 'LOCAL' OR (email IS NOT NULL AND password_hash IS NOT NULL))
);

-- 이메일 가입 계정끼리만 이메일 중복을 막는다.
-- 카카오 계정은 같은 이메일이어도 별도 계정으로 둔다 (자동 병합 금지 결정, ADR-006)
CREATE UNIQUE INDEX uk_landlord_local_email ON landlord (email) WHERE auth_provider = 'LOCAL';
