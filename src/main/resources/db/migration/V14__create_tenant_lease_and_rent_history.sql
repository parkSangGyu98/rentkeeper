-- 세입자(tenant), 계약(lease), 월세 이력(lease_rent_history) (PRD 4.3 LEASE-1~4, ADR-017·018·019).
-- 개인정보(세입자 이름·연락처·메모, 계좌번호·예금주)는 앱에서 AES-256-GCM으로 암호화해 "v키버전:..." 형식으로 저장한다(ADR-017).
-- 평문이 실수로 들어가지 않게 형식 CHECK를 둔다. 전화번호 중복 확인은 HMAC(phone_hash)으로 한다.
-- 금액은 원 단위 bigint(ADR-003). 계약의 월세·관리비는 lease가 아니라 lease_rent_history만 기준이다(M2 결정 5).

CREATE TABLE tenant (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    landlord_id bigint        NOT NULL,
    name_enc    varchar(512)  NOT NULL,
    phone_enc   varchar(512)  NOT NULL,
    phone_hash  char(64)      NOT NULL,
    -- 메모는 500자까지. 암호문 길이 = 3 + ceil((UTF-8 바이트 + 28) / 3) * 4 ("v1:" 기준, 키 버전 한 자리 늘 때마다 +1).
    -- 최악(이모지 500자 = 2,000바이트, 키 버전 네 자리) 2,710자 → 여유를 두어 2,800 (EncryptedColumnLengthTests가 확인)
    memo_enc    varchar(2800),
    created_at  timestamptz   NOT NULL,
    updated_at  timestamptz   NOT NULL,
    CONSTRAINT fk_tenant_landlord FOREIGN KEY (landlord_id) REFERENCES landlord (id) ON DELETE CASCADE,
    CONSTRAINT ck_tenant_encrypted CHECK (
        name_enc ~ '^v[0-9]+:[A-Za-z0-9_-]+$'
        AND phone_enc ~ '^v[0-9]+:[A-Za-z0-9_-]+$'
        AND (memo_enc IS NULL OR memo_enc ~ '^v[0-9]+:[A-Za-z0-9_-]+$')),
    CONSTRAINT ck_tenant_phone_hash CHECK (phone_hash ~ '^[0-9a-f]{64}$'),
    -- 같은 임대인 안에서 연락처 중복 불가 (M2 결정 6). landlord_id로 시작하므로 "임대인의 세입자" 조회를 겸한다
    CONSTRAINT uk_tenant_landlord_phone UNIQUE (landlord_id, phone_hash),
    CONSTRAINT uk_tenant_id_landlord UNIQUE (id, landlord_id)
);

CREATE TABLE lease (
    id                 bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    landlord_id        bigint       NOT NULL,
    unit_id            bigint       NOT NULL,
    tenant_id          bigint       NOT NULL,
    deposit            bigint       NOT NULL,
    due_day            smallint     NOT NULL,
    start_date         date         NOT NULL,
    end_date           date         NOT NULL,
    status             varchar(10)  NOT NULL,
    ended_on           date,
    bank_name          varchar(50)  NOT NULL,
    account_no_enc     varchar(512) NOT NULL,
    account_holder_enc varchar(512) NOT NULL,
    memo               varchar(500),
    created_at         timestamptz  NOT NULL,
    updated_at         timestamptz  NOT NULL,
    CONSTRAINT fk_lease_landlord FOREIGN KEY (landlord_id) REFERENCES landlord (id) ON DELETE CASCADE,
    -- 같은 임대인의 호실·세입자만 (복합 FK, ADR-018). ON DELETE는 기본값(NO ACTION): 계약 기록이 있는 호실·세입자는 지울 수 없다
    CONSTRAINT fk_lease_unit FOREIGN KEY (unit_id, landlord_id) REFERENCES unit (id, landlord_id),
    CONSTRAINT fk_lease_tenant FOREIGN KEY (tenant_id, landlord_id) REFERENCES tenant (id, landlord_id),
    CONSTRAINT ck_lease_deposit CHECK (deposit BETWEEN 0 AND 10000000000),
    CONSTRAINT ck_lease_due_day CHECK (due_day BETWEEN 1 AND 31),
    CONSTRAINT ck_lease_period CHECK (end_date > start_date),
    CONSTRAINT ck_lease_status CHECK (status IN ('ACTIVE', 'ENDED')),
    -- "오늘까지"는 DB가 알 수 없어(Clock) 앱에서 검사한다 (M2 결정 3)
    CONSTRAINT ck_lease_ended CHECK (
        (status = 'ENDED') = (ended_on IS NOT NULL)
        AND (ended_on IS NULL OR ended_on >= start_date)),
    CONSTRAINT ck_lease_bank_name CHECK (bank_name = btrim(bank_name) AND bank_name <> ''),
    CONSTRAINT ck_lease_encrypted CHECK (
        account_no_enc ~ '^v[0-9]+:[A-Za-z0-9_-]+$'
        AND account_holder_enc ~ '^v[0-9]+:[A-Za-z0-9_-]+$'),
    CONSTRAINT uk_lease_id_landlord UNIQUE (id, landlord_id),
    -- 같은 호실의 활성(ACTIVE) 계약끼리 기간이 겹치면 안 된다 (LEASE-4, ADR-019). btree_gist 확장은 V1에서 만들었다.
    -- '[)': 들어오는 날(start_date)은 포함, 나가는 날(end_date)은 제외 → 앞 세입자가 나가는 날에 다음 세입자가 들어올 수 있다(M2 결정 2).
    -- 종료 처리한(ENDED) 계약은 검사에서 빠진다.
    CONSTRAINT ex_lease_unit_active_period EXCLUDE USING gist (
        unit_id WITH =,
        daterange(start_date, end_date, '[)') WITH &&
    ) WHERE (status = 'ACTIVE')
);

-- 호실의 계약 목록(종료된 것 포함)과 fk_lease_unit 검사. 위 EXCLUDE 인덱스는 ACTIVE만 담는 부분 인덱스라 대신하지 못한다
CREATE INDEX ix_lease_unit ON lease (unit_id, start_date);
-- 세입자의 계약 찾기와 fk_lease_tenant 검사
CREATE INDEX ix_lease_tenant ON lease (tenant_id);
-- 임대인의 활성 계약 목록, 직전 계약 계좌 찾기(M2 결정 20), 임대인 파기(CASCADE)
CREATE INDEX ix_lease_landlord_status ON lease (landlord_id, status);

CREATE TABLE lease_rent_history (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    landlord_id     bigint       NOT NULL,
    lease_id        bigint       NOT NULL,
    monthly_rent    bigint       NOT NULL,
    maintenance_fee bigint       NOT NULL,
    effective_from  date         NOT NULL,
    reason          varchar(200),
    created_at      timestamptz  NOT NULL,
    updated_at      timestamptz  NOT NULL,
    CONSTRAINT fk_lease_rent_history_landlord FOREIGN KEY (landlord_id) REFERENCES landlord (id) ON DELETE CASCADE,
    -- 같은 임대인의 계약만. 계약을 지우면(청구가 없을 때만 허용, M2 결정 11) 이력도 함께 지운다
    CONSTRAINT fk_lease_rent_history_lease FOREIGN KEY (lease_id, landlord_id) REFERENCES lease (id, landlord_id)
        ON DELETE CASCADE,
    CONSTRAINT ck_lease_rent_history_monthly_rent CHECK (monthly_rent BETWEEN 0 AND 100000000),
    CONSTRAINT ck_lease_rent_history_maintenance_fee CHECK (maintenance_fee BETWEEN 0 AND 100000000),
    CONSTRAINT ck_lease_rent_history_effective_from CHECK (extract(day FROM effective_from) = 1),
    -- 한 계약에 같은 달부터 적용되는 행은 하나. lease_id로 시작하므로 "계약의 이력" 조회와 FK 검사를 겸한다
    CONSTRAINT uk_lease_rent_history_lease_month UNIQUE (lease_id, effective_from)
);

-- 임대인 파기(CASCADE) 때 그 임대인의 이력을 찾는다
CREATE INDEX ix_lease_rent_history_landlord ON lease_rent_history (landlord_id);

COMMENT ON TABLE tenant IS '세입자 (LEASE-1). 임대인이 등록한 사람 한 명이 한 행. 같은 임대인 안에서 연락처가 같으면 같은 세입자로 다시 쓴다(M2 결정 6). 개인정보는 암호화해 저장한다(ADR-017). 보관 기간은 미정이라 임대인 탈퇴 파기로만 지워진다(M2 결정 19).';
COMMENT ON COLUMN tenant.id IS '세입자 ID.';
COMMENT ON COLUMN tenant.landlord_id IS '세입자를 등록한 임대인 (landlord.id). 모든 조회·수정은 이 값으로 소유권을 확인한다(남의 세입자는 404).';
COMMENT ON COLUMN tenant.name_enc IS '세입자 이름의 암호문 (AES-256-GCM, "v키버전:base64url" 형식). 키 없이는 읽을 수 없다.';
COMMENT ON COLUMN tenant.phone_enc IS '휴대폰 번호(숫자만, 01X로 시작)의 암호문 (AES-256-GCM, "v키버전:base64url" 형식).';
COMMENT ON COLUMN tenant.phone_hash IS '휴대폰 번호의 HMAC-SHA256 (소문자 hex 64자). 같은 임대인 안 중복 확인용. 암호문은 매번 달라 비교할 수 없어서 따로 둔다(ADR-017).';
COMMENT ON COLUMN tenant.memo_enc IS '세입자 메모(500자까지)의 암호문 (AES-256-GCM, M2 결정 9). 한글 500자·이모지 500자까지 저장되는 길이다(최악 2,710자, 키 버전 네 자리 포함). NULL=메모 없음.';
COMMENT ON COLUMN tenant.created_at IS '등록 시각 (UTC, 앱의 Clock 기준).';
COMMENT ON COLUMN tenant.updated_at IS '마지막 수정 시각 (UTC, 앱의 Clock 기준).';
COMMENT ON CONSTRAINT tenant_pkey ON tenant IS '세입자 ID 기본 키.';
COMMENT ON INDEX tenant_pkey IS '세입자 ID 기본 키 인덱스.';
COMMENT ON CONSTRAINT fk_tenant_landlord ON tenant IS '세입자를 등록한 임대인. 임대인이 지워지면(탈퇴 30일 뒤 파기) 세입자도 지운다.';
COMMENT ON CONSTRAINT ck_tenant_encrypted ON tenant IS '이름·연락처·메모는 암호문 형식("v키버전:base64url")이어야 한다. 평문이 실수로 저장되는 것을 막는다(ADR-017).';
COMMENT ON CONSTRAINT ck_tenant_phone_hash ON tenant IS '연락처 HMAC은 소문자 hex 64자.';
COMMENT ON CONSTRAINT uk_tenant_landlord_phone ON tenant IS '같은 임대인 안에서 연락처(HMAC) 중복 불가 (M2 결정 6).';
COMMENT ON INDEX uk_tenant_landlord_phone IS '같은 임대인 안 연락처 중복 방지 + 같은 번호의 기존 세입자 찾기.';
COMMENT ON CONSTRAINT uk_tenant_id_landlord ON tenant IS '계약(lease)이 (세입자 ID, 임대인 ID)로 같은 임대인의 세입자만 가리키게 하는 복합 FK 대상 (ADR-018).';
COMMENT ON INDEX uk_tenant_id_landlord IS '복합 FK fk_lease_tenant가 참조하는 인덱스.';

COMMENT ON TABLE lease IS '임대 계약 (LEASE-1~4). 호실 하나에 세입자 한 명의 계약 하나가 한 행. 월세·관리비는 lease_rent_history에 있다(M2 결정 5). 임대인 탈퇴 파기 때 함께 지워진다.';
COMMENT ON COLUMN lease.id IS '계약 ID.';
COMMENT ON COLUMN lease.landlord_id IS '계약한 임대인 (landlord.id). 호실·세입자의 임대인과 같아야 한다(복합 FK).';
COMMENT ON COLUMN lease.unit_id IS '계약한 호실 (unit.id). 계약 수정으로 바꿀 수 없다(M2 결정 12).';
COMMENT ON COLUMN lease.tenant_id IS '세입자 (tenant.id).';
COMMENT ON COLUMN lease.deposit IS '보증금 (원). 0 이상 100억 이하(M2 결정 8). 이력은 남기지 않는다(LEASE-7은 P1).';
COMMENT ON COLUMN lease.due_day IS '매달 월세 받는 날 (1~31). 그 달에 없는 날(예: 31일, 2월)은 말일로 보정한다(보정 계산은 M3 청구).';
COMMENT ON COLUMN lease.start_date IS '들어오는 날 (계약 시작일, 그날 포함). 계약 수정으로 바꿀 수 없다(M2 결정 12).';
COMMENT ON COLUMN lease.end_date IS '나가는 날 (계약 종료일). 기간 겹침 검사에서는 이 날을 빼고 본다([) 범위): 같은 날 다음 세입자가 들어올 수 있다(M2 결정 2). 연장·단축할 수 있다.';
COMMENT ON COLUMN lease.status IS '계약 상태. ACTIVE=진행 중(종료 처리 전, 나가는 날이 지나도 그대로), ENDED=종료 처리함(이후 청구를 만들지 않음, LEASE-3). ENDED에서 되돌릴 수 없다.';
COMMENT ON COLUMN lease.ended_on IS '실제로 끝난 날 (종료 처리할 때 입력). NULL=종료 처리 전(ACTIVE). 들어오는 날 이후 ~ 처리한 날(오늘)까지만 허용(M2 결정 3).';
COMMENT ON COLUMN lease.bank_name IS '월세 받을 은행·조합 이름 (예: 국민은행, 새마을금고). 앞뒤 공백 없이 1~50자. 평문.';
COMMENT ON COLUMN lease.account_no_enc IS '월세 받을 계좌번호(숫자만)의 암호문 (AES-256-GCM, "v키버전:base64url" 형식). 계약마다 필수(M2 결정 20).';
COMMENT ON COLUMN lease.account_holder_enc IS '예금주 이름의 암호문 (AES-256-GCM, M2 결정 9).';
COMMENT ON COLUMN lease.memo IS '계약 메모. 평문이라 화면에서 "주민번호·계좌번호는 적지 마세요"라고 안내한다.';
COMMENT ON COLUMN lease.created_at IS '등록 시각 (UTC, 앱의 Clock 기준).';
COMMENT ON COLUMN lease.updated_at IS '마지막 수정 시각 (UTC, 앱의 Clock 기준).';
COMMENT ON CONSTRAINT lease_pkey ON lease IS '계약 ID 기본 키.';
COMMENT ON INDEX lease_pkey IS '계약 ID 기본 키 인덱스.';
COMMENT ON CONSTRAINT fk_lease_landlord ON lease IS '계약한 임대인. 임대인이 지워지면(탈퇴 30일 뒤 파기) 계약도 지운다.';
COMMENT ON CONSTRAINT fk_lease_unit ON lease IS '같은 임대인의 호실만 가리킨다(복합 FK, ADR-018). 계약 기록이 있는 호실은 지울 수 없다(NO ACTION).';
COMMENT ON CONSTRAINT fk_lease_tenant ON lease IS '같은 임대인의 세입자만 가리킨다(복합 FK, ADR-018). 계약이 있는 세입자는 지울 수 없다(NO ACTION).';
COMMENT ON CONSTRAINT ck_lease_deposit ON lease IS '보증금은 0원 이상 100억 원 이하 (M2 결정 8).';
COMMENT ON CONSTRAINT ck_lease_due_day ON lease IS '월세 받는 날은 1~31일.';
COMMENT ON CONSTRAINT ck_lease_period ON lease IS '나가는 날은 들어오는 날보다 뒤여야 한다 (하루 이상).';
COMMENT ON CONSTRAINT ck_lease_status ON lease IS '계약 상태는 ACTIVE(진행 중), ENDED(종료 처리함)만.';
COMMENT ON CONSTRAINT ck_lease_ended ON lease IS 'ENDED이면 실제 종료일이 있고 ACTIVE이면 없다. 실제 종료일은 들어오는 날 이후.';
COMMENT ON CONSTRAINT ck_lease_bank_name ON lease IS '은행 이름은 앞뒤 공백 없이 비어 있지 않아야 한다.';
COMMENT ON CONSTRAINT ck_lease_encrypted ON lease IS '계좌번호·예금주는 암호문 형식("v키버전:base64url")이어야 한다. 평문이 실수로 저장되는 것을 막는다(ADR-017).';
COMMENT ON CONSTRAINT uk_lease_id_landlord ON lease IS '월세 이력(lease_rent_history)이 (계약 ID, 임대인 ID)로 같은 임대인의 계약만 가리키게 하는 복합 FK 대상 (ADR-018).';
COMMENT ON INDEX uk_lease_id_landlord IS '복합 FK fk_lease_rent_history_lease가 참조하는 인덱스.';
COMMENT ON CONSTRAINT ex_lease_unit_active_period ON lease IS '같은 호실의 ACTIVE 계약끼리 기간이 겹치면 안 된다 (LEASE-4, ADR-019). [들어오는 날, 나가는 날) 범위라 나가는 날에 다음 계약이 시작할 수 있다. 종료 처리한 계약은 빠진다.';
COMMENT ON INDEX ex_lease_unit_active_period IS '활성 계약 기간 겹침 검사용 GiST 부분 인덱스 (status=ACTIVE만).';
COMMENT ON INDEX ix_lease_unit IS '호실의 계약 목록(종료 포함, 시작일 순)과 fk_lease_unit 검사.';
COMMENT ON INDEX ix_lease_tenant IS '세입자의 계약 찾기와 fk_lease_tenant 검사.';
COMMENT ON INDEX ix_lease_landlord_status IS '임대인의 활성 계약 목록, 직전 계약 계좌 찾기, 임대인 파기(CASCADE).';

COMMENT ON TABLE lease_rent_history IS '계약의 월세·관리비 이력 (LEASE-2). 어떤 달의 금액 = 그 달 이하에서 가장 최근 행(effective_from). 계약을 등록하면 들어오는 달 1일로 첫 행이 생긴다. lease에는 금액이 없고 이 표만 기준이다(M2 결정 5).';
COMMENT ON COLUMN lease_rent_history.id IS '이력 ID.';
COMMENT ON COLUMN lease_rent_history.landlord_id IS '계약한 임대인 (landlord.id). 계약의 임대인과 같아야 한다(복합 FK).';
COMMENT ON COLUMN lease_rent_history.lease_id IS '이력이 속한 계약 (lease.id).';
COMMENT ON COLUMN lease_rent_history.monthly_rent IS '월세 (원). 0 이상 1억 이하.';
COMMENT ON COLUMN lease_rent_history.maintenance_fee IS '고정 관리비 (원). 0 이상 1억 이하. 없으면 0.';
COMMENT ON COLUMN lease_rent_history.effective_from IS '이 금액을 적용하기 시작하는 청구월 (그 달 1일). 새 변경은 이번 달 이후·계약 기간 안만. 아직 시작하지 않은 달의 행은 바꾸거나 취소할 수 있고, 이미 시작된 행은 고칠 수 없다(M2 결정 4).';
COMMENT ON COLUMN lease_rent_history.reason IS '바꾼 이유 (예: 재계약 인상). 선택. NULL=적지 않음.';
COMMENT ON COLUMN lease_rent_history.created_at IS '등록 시각 (UTC, 앱의 Clock 기준).';
COMMENT ON COLUMN lease_rent_history.updated_at IS '마지막 수정 시각 (UTC, 앱의 Clock 기준).';
COMMENT ON CONSTRAINT lease_rent_history_pkey ON lease_rent_history IS '이력 ID 기본 키.';
COMMENT ON INDEX lease_rent_history_pkey IS '이력 ID 기본 키 인덱스.';
COMMENT ON CONSTRAINT fk_lease_rent_history_landlord ON lease_rent_history IS '계약한 임대인. 임대인이 지워지면(탈퇴 30일 뒤 파기) 이력도 지운다.';
COMMENT ON CONSTRAINT fk_lease_rent_history_lease ON lease_rent_history IS '같은 임대인의 계약만 가리킨다(복합 FK, ADR-018). 계약을 지우면 이력도 함께 지운다.';
COMMENT ON CONSTRAINT ck_lease_rent_history_monthly_rent ON lease_rent_history IS '월세는 0원 이상 1억 원 이하 (PRD 4.3).';
COMMENT ON CONSTRAINT ck_lease_rent_history_maintenance_fee ON lease_rent_history IS '관리비는 0원 이상 1억 원 이하 (PRD 4.3).';
COMMENT ON CONSTRAINT ck_lease_rent_history_effective_from ON lease_rent_history IS '적용 시작월은 그 달 1일로 저장한다 (청구월 기준).';
COMMENT ON CONSTRAINT uk_lease_rent_history_lease_month ON lease_rent_history IS '한 계약에 같은 달부터 적용되는 금액은 하나.';
COMMENT ON INDEX uk_lease_rent_history_lease_month IS '계약의 같은 달 중복 방지 + 계약의 이력 조회 + fk_lease_rent_history_lease 검사.';
COMMENT ON INDEX ix_lease_rent_history_landlord IS '임대인 파기(CASCADE) 때 그 임대인의 이력 찾기.';
