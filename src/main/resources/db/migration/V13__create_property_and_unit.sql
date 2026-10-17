-- 건물(property)과 호실(unit) (PRD 4.2 PROP-1~3, ADR-018).
-- 모든 임대인 소유 테이블은 landlord_id를 갖고 ON DELETE CASCADE다: 탈퇴 30일 뒤 파기 배치가 landlord 행을 지우면 함께 지워진다(ADR-015).
-- 자식 테이블은 (부모_id, landlord_id) 복합 FK로 부모를 가리킨다: 다른 임대인의 건물에 호실을 붙이는 일을 DB에서 막는다(ADR-018).
-- 호실 상태(공실/입주중/입주 예정/기간 지남)는 저장하지 않고 계약에서 계산한다(M2 결정 1).

CREATE TABLE property (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    landlord_id bigint       NOT NULL,
    name        varchar(50)  NOT NULL,
    address     varchar(200),
    memo        varchar(500),
    -- 시각은 앱의 Clock이 채운다 (ADR-002)
    created_at  timestamptz  NOT NULL,
    updated_at  timestamptz  NOT NULL,
    CONSTRAINT fk_property_landlord FOREIGN KEY (landlord_id) REFERENCES landlord (id) ON DELETE CASCADE,
    CONSTRAINT ck_property_name CHECK (name = btrim(name) AND name <> ''),
    CONSTRAINT ck_property_address CHECK (address IS NULL OR (address = btrim(address) AND address <> '')),
    -- 같은 임대인 안에서 건물 이름 중복 불가 (M2 결정 21). landlord_id로 시작하므로 "임대인의 건물 목록" 조회 인덱스를 겸한다
    CONSTRAINT uk_property_landlord_name UNIQUE (landlord_id, name),
    -- 자식(unit)의 복합 FK가 가리킬 대상
    CONSTRAINT uk_property_id_landlord UNIQUE (id, landlord_id)
);

CREATE TABLE unit (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    landlord_id bigint       NOT NULL,
    property_id bigint       NOT NULL,
    name        varchar(20)  NOT NULL,
    type        varchar(20)  NOT NULL,
    memo        varchar(500),
    created_at  timestamptz  NOT NULL,
    updated_at  timestamptz  NOT NULL,
    CONSTRAINT fk_unit_landlord FOREIGN KEY (landlord_id) REFERENCES landlord (id) ON DELETE CASCADE,
    -- 같은 임대인의 건물만. ON DELETE는 기본값(NO ACTION): 호실이 있는 건물은 지울 수 없다(M2 결정 11).
    -- 임대인 파기 때는 건물·호실이 한 문장 안에서 함께 CASCADE로 지워지고, NO ACTION은 문장 끝에 검사하므로 막지 않는다
    CONSTRAINT fk_unit_property FOREIGN KEY (property_id, landlord_id) REFERENCES property (id, landlord_id),
    CONSTRAINT ck_unit_name CHECK (name = btrim(name) AND name <> ''),
    CONSTRAINT ck_unit_type CHECK (type IN ('ONE_ROOM', 'TWO_ROOM', 'THREE_PLUS', 'STORE', 'OFFICE', 'OTHER')),
    -- 건물 안 호실명 중복 불가 (PROP-2). property_id로 시작하므로 "건물의 호실 목록" 조회와 위 FK 인덱스를 겸한다
    CONSTRAINT uk_unit_property_name UNIQUE (property_id, name),
    -- 자식(lease)의 복합 FK가 가리킬 대상
    CONSTRAINT uk_unit_id_landlord UNIQUE (id, landlord_id)
);

-- 임대인 파기(CASCADE) 때 그 임대인의 호실을 찾는다
CREATE INDEX ix_unit_landlord ON unit (landlord_id);

COMMENT ON TABLE property IS '건물 (PROP-1). 임대인이 가진 건물 하나가 한 행. 임대인 탈퇴 파기 때 함께 지워진다.';
COMMENT ON COLUMN property.id IS '건물 ID.';
COMMENT ON COLUMN property.landlord_id IS '건물 주인 임대인 (landlord.id). 모든 조회·수정은 이 값으로 소유권을 확인한다(남의 건물은 404).';
COMMENT ON COLUMN property.name IS '건물 이름 (예: 행복빌라). 앞뒤 공백 없이 1~50자. 같은 임대인 안에서 중복 불가.';
COMMENT ON COLUMN property.address IS '주소. 선택. 평문으로 저장한다(건물 주소는 암호화 대상이 아님).';
COMMENT ON COLUMN property.memo IS '임대인 메모. 평문이라 화면에서 "주민번호·계좌번호는 적지 마세요"라고 안내한다.';
COMMENT ON COLUMN property.created_at IS '등록 시각 (UTC, 앱의 Clock 기준).';
COMMENT ON COLUMN property.updated_at IS '마지막 수정 시각 (UTC, 앱의 Clock 기준).';
COMMENT ON CONSTRAINT property_pkey ON property IS '건물 ID 기본 키.';
COMMENT ON INDEX property_pkey IS '건물 ID 기본 키 인덱스.';
COMMENT ON CONSTRAINT fk_property_landlord ON property IS '건물 주인. 임대인이 지워지면(탈퇴 30일 뒤 파기) 건물도 지운다.';
COMMENT ON CONSTRAINT ck_property_name ON property IS '건물 이름은 앞뒤 공백 없이 비어 있지 않아야 한다.';
COMMENT ON CONSTRAINT ck_property_address ON property IS '주소는 없거나, 앞뒤 공백 없이 비어 있지 않아야 한다(빈 문자열 대신 NULL).';
COMMENT ON CONSTRAINT uk_property_landlord_name ON property IS '같은 임대인 안에서 건물 이름 중복 불가 (M2 결정 21).';
COMMENT ON INDEX uk_property_landlord_name IS '같은 임대인 안 건물 이름 중복 방지 + 임대인의 건물 목록 조회.';
COMMENT ON CONSTRAINT uk_property_id_landlord ON property IS '호실(unit)이 (건물 ID, 임대인 ID)로 같은 임대인의 건물만 가리키게 하는 복합 FK 대상 (ADR-018).';
COMMENT ON INDEX uk_property_id_landlord IS '복합 FK fk_unit_property가 참조하는 인덱스.';

COMMENT ON TABLE unit IS '호실 (PROP-2). 건물 안의 방·점포 하나가 한 행. 상태(공실/입주중/입주 예정/기간 지남)는 저장하지 않고 계약에서 계산한다(PROP-3). 임대인 탈퇴 파기 때 함께 지워진다.';
COMMENT ON COLUMN unit.id IS '호실 ID.';
COMMENT ON COLUMN unit.landlord_id IS '호실 주인 임대인 (landlord.id). 건물의 임대인과 같아야 한다(fk_unit_property).';
COMMENT ON COLUMN unit.property_id IS '호실이 속한 건물 (property.id).';
COMMENT ON COLUMN unit.name IS '호실 이름 (예: 101호). 앞뒤 공백 없이 1~20자. 같은 건물 안에서 중복 불가.';
COMMENT ON COLUMN unit.type IS '호실 유형. ONE_ROOM=원룸, TWO_ROOM=투룸, THREE_PLUS=쓰리룸 이상, STORE=상가, OFFICE=사무실, OTHER=기타.';
COMMENT ON COLUMN unit.memo IS '임대인 메모. 평문이라 화면에서 "주민번호·계좌번호는 적지 마세요"라고 안내한다.';
COMMENT ON COLUMN unit.created_at IS '등록 시각 (UTC, 앱의 Clock 기준).';
COMMENT ON COLUMN unit.updated_at IS '마지막 수정 시각 (UTC, 앱의 Clock 기준).';
COMMENT ON CONSTRAINT unit_pkey ON unit IS '호실 ID 기본 키.';
COMMENT ON INDEX unit_pkey IS '호실 ID 기본 키 인덱스.';
COMMENT ON CONSTRAINT fk_unit_landlord ON unit IS '호실 주인. 임대인이 지워지면(탈퇴 30일 뒤 파기) 호실도 지운다.';
COMMENT ON CONSTRAINT fk_unit_property ON unit IS '같은 임대인의 건물만 가리킨다(복합 FK, ADR-018). 호실이 있는 건물은 지울 수 없다(NO ACTION).';
COMMENT ON CONSTRAINT ck_unit_name ON unit IS '호실 이름은 앞뒤 공백 없이 비어 있지 않아야 한다.';
COMMENT ON CONSTRAINT ck_unit_type ON unit IS '호실 유형은 ONE_ROOM, TWO_ROOM, THREE_PLUS, STORE, OFFICE, OTHER만.';
COMMENT ON CONSTRAINT uk_unit_property_name ON unit IS '같은 건물 안에서 호실 이름 중복 불가 (PROP-2).';
COMMENT ON INDEX uk_unit_property_name IS '건물 안 호실 이름 중복 방지 + 건물의 호실 목록 조회 + fk_unit_property 검사.';
COMMENT ON CONSTRAINT uk_unit_id_landlord ON unit IS '계약(lease)이 (호실 ID, 임대인 ID)로 같은 임대인의 호실만 가리키게 하는 복합 FK 대상 (ADR-018).';
COMMENT ON INDEX uk_unit_id_landlord IS '복합 FK fk_lease_unit이 참조하는 인덱스.';
COMMENT ON INDEX ix_unit_landlord IS '임대인 파기(CASCADE) 때 그 임대인의 호실 찾기.';
