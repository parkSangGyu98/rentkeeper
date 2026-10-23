-- 청구(받을 월세)와 항목, 입금 기록, 상태 이력, 항목 변경 이력 (PRD 4.4, ADR-023·024·025).
-- 모든 테이블은 landlord_id ON DELETE CASCADE다(탈퇴 30일 뒤 파기, ADR-015). 자식은 (부모_id, landlord_id) 복합 FK로 같은 임대인의 것만 가리킨다(ADR-018).
-- 금액은 원 단위 bigint(ADR-003), 시각은 앱 Clock 기준 UTC, 청구월·납부일 같은 업무 날짜는 서울 기준 date다(ADR-002).

-- 계약마다 "납부일 며칠 전에 받을 월세를 만들지" (M2 결정 18, M3 결정 17). 기존 계약은 기본값 7일
ALTER TABLE lease ADD COLUMN issue_days_before smallint NOT NULL DEFAULT 7;
ALTER TABLE lease ADD CONSTRAINT ck_lease_issue_days_before CHECK (issue_days_before BETWEEN 0 AND 28);

CREATE TABLE charge (
    id                 bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    landlord_id        bigint       NOT NULL,
    lease_id           bigint       NOT NULL,
    billing_month      date         NOT NULL,
    due_date           date         NOT NULL,
    total_amount       bigint       NOT NULL,
    paid_amount        bigint       NOT NULL DEFAULT 0,
    status             varchar(20)  NOT NULL,
    source             varchar(10)  NOT NULL,
    issued_at          timestamptz,
    tenant_reported_at timestamptz,
    paid_at            timestamptz,
    canceled_at        timestamptz,
    cancel_reason      varchar(200),
    -- 낙관적 락 (PRD 4.4.3): 입금·수정할 때마다 1씩 오른다. 화면이 보던 version과 다르면 저장하지 않는다
    version            bigint       NOT NULL DEFAULT 0,
    created_at         timestamptz  NOT NULL,
    updated_at         timestamptz  NOT NULL,
    CONSTRAINT fk_charge_landlord FOREIGN KEY (landlord_id) REFERENCES landlord (id) ON DELETE CASCADE,
    -- 같은 임대인의 계약만. ON DELETE는 기본값(NO ACTION): 청구가 있는 계약은 지울 수 없다(M2 결정 11, M3 필수 확인).
    -- 임대인 파기 때는 한 문장 안에서 함께 CASCADE로 지워지고 NO ACTION은 문장 끝에 검사하므로 막지 않는다
    CONSTRAINT fk_charge_lease FOREIGN KEY (lease_id, landlord_id) REFERENCES lease (id, landlord_id),
    -- 멱등 (PRD 4.4.1): 계약 하나에 같은 달 청구는 하나. 취소한 달도 포함한다 → 취소한 달은 다시 만들 수 없다(M3 결정 8)
    CONSTRAINT uk_charge_lease_month UNIQUE (lease_id, billing_month),
    CONSTRAINT uk_charge_id_landlord UNIQUE (id, landlord_id),
    CONSTRAINT ck_charge_billing_month CHECK (extract(day FROM billing_month) = 1),
    CONSTRAINT ck_charge_amounts CHECK (total_amount >= 0 AND paid_amount >= 0),
    CONSTRAINT ck_charge_status CHECK (status IN
        ('SCHEDULED', 'ISSUED', 'TENANT_REPORTED', 'PARTIALLY_PAID', 'PAID', 'OVERDUE', 'CANCELED')),
    CONSTRAINT ck_charge_source CHECK (source IN ('AUTO', 'MANUAL')),
    CONSTRAINT ck_charge_version CHECK (version >= 0),
    -- 상태와 시각 컬럼이 서로 맞아야 한다
    CONSTRAINT ck_charge_paid_at CHECK ((status = 'PAID') = (paid_at IS NOT NULL)),
    CONSTRAINT ck_charge_canceled CHECK (
        (status = 'CANCELED') = (canceled_at IS NOT NULL)
        AND (canceled_at IS NULL) = (cancel_reason IS NULL)),
    CONSTRAINT ck_charge_issued_at CHECK (
        (status <> 'SCHEDULED' OR issued_at IS NULL)
        AND (status NOT IN ('ISSUED', 'TENANT_REPORTED', 'OVERDUE') OR issued_at IS NOT NULL)),
    CONSTRAINT ck_charge_tenant_reported_at CHECK (status <> 'TENANT_REPORTED' OR tenant_reported_at IS NOT NULL)
);

-- 월별 목록 (M3-b 필터 포함). landlord_id로 시작해 임대인 파기(CASCADE)도 겸한다
CREATE INDEX ix_charge_landlord_month_status ON charge (landlord_id, billing_month, status);
-- 납부일 지남 배치 (00:10): 상태와 납부일로 대상 찾기
CREATE INDEX ix_charge_status_due_date ON charge (status, due_date);

CREATE TABLE charge_item (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    landlord_id bigint       NOT NULL,
    charge_id   bigint       NOT NULL,
    type        varchar(20)  NOT NULL,
    label       varchar(50)  NOT NULL,
    amount      bigint       NOT NULL,
    memo        varchar(200),
    created_at  timestamptz  NOT NULL,
    updated_at  timestamptz  NOT NULL,
    CONSTRAINT fk_charge_item_landlord FOREIGN KEY (landlord_id) REFERENCES landlord (id) ON DELETE CASCADE,
    CONSTRAINT fk_charge_item_charge FOREIGN KEY (charge_id, landlord_id) REFERENCES charge (id, landlord_id)
        ON DELETE CASCADE,
    CONSTRAINT ck_charge_item_type CHECK (type IN ('RENT', 'MAINTENANCE', 'ELECTRIC', 'WATER', 'GAS', 'OTHER')),
    CONSTRAINT ck_charge_item_label CHECK (label = btrim(label) AND label <> ''),
    CONSTRAINT ck_charge_item_amount CHECK (amount BETWEEN 0 AND 100000000)
);

CREATE INDEX ix_charge_item_charge ON charge_item (charge_id);
CREATE INDEX ix_charge_item_landlord ON charge_item (landlord_id);

CREATE TABLE payment_record (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    landlord_id   bigint       NOT NULL,
    charge_id     bigint       NOT NULL,
    amount        bigint       NOT NULL,
    paid_on       date         NOT NULL,
    source        varchar(20)  NOT NULL,
    memo          varchar(200),
    -- 정정하면 원래 기록은 지우지 않고 표시만 한다(감사 기록, M3 결정 11)
    deleted_at    timestamptz,
    delete_reason varchar(200),
    replaces_id   bigint,
    created_at    timestamptz  NOT NULL,
    updated_at    timestamptz  NOT NULL,
    CONSTRAINT fk_payment_record_landlord FOREIGN KEY (landlord_id) REFERENCES landlord (id) ON DELETE CASCADE,
    CONSTRAINT fk_payment_record_charge FOREIGN KEY (charge_id, landlord_id) REFERENCES charge (id, landlord_id)
        ON DELETE CASCADE,
    CONSTRAINT uk_payment_record_id_charge UNIQUE (id, charge_id),
    -- 정정한 원래 기록은 같은 청구의 기록이어야 한다
    CONSTRAINT fk_payment_record_replaces FOREIGN KEY (replaces_id, charge_id) REFERENCES payment_record (id, charge_id),
    CONSTRAINT ck_payment_record_amount CHECK (amount > 0 AND amount <= 1000000000),
    CONSTRAINT ck_payment_record_source CHECK (source IN ('MANUAL', 'TENANT_REPORT')),
    CONSTRAINT ck_payment_record_deleted CHECK ((deleted_at IS NULL) = (delete_reason IS NULL)),
    CONSTRAINT ck_payment_record_memo CHECK (memo IS NULL OR memo <> '')
);

-- 청구의 지우지 않은 입금 (받은 금액 합계)
CREATE INDEX ix_payment_record_charge_active ON payment_record (charge_id) WHERE deleted_at IS NULL;
CREATE INDEX ix_payment_record_landlord ON payment_record (landlord_id);

CREATE TABLE charge_status_history (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    landlord_id bigint       NOT NULL,
    charge_id   bigint       NOT NULL,
    from_status varchar(20),
    to_status   varchar(20)  NOT NULL,
    actor_type  varchar(10)  NOT NULL,
    actor_id    bigint,
    reason      varchar(200),
    created_at  timestamptz  NOT NULL,
    CONSTRAINT fk_charge_status_history_landlord FOREIGN KEY (landlord_id) REFERENCES landlord (id) ON DELETE CASCADE,
    CONSTRAINT fk_charge_status_history_charge FOREIGN KEY (charge_id, landlord_id) REFERENCES charge (id, landlord_id)
        ON DELETE CASCADE,
    CONSTRAINT ck_charge_status_history_from CHECK (from_status IS NULL OR from_status IN
        ('SCHEDULED', 'ISSUED', 'TENANT_REPORTED', 'PARTIALLY_PAID', 'PAID', 'OVERDUE', 'CANCELED')),
    CONSTRAINT ck_charge_status_history_to CHECK (to_status IN
        ('SCHEDULED', 'ISSUED', 'TENANT_REPORTED', 'PARTIALLY_PAID', 'PAID', 'OVERDUE', 'CANCELED')),
    CONSTRAINT ck_charge_status_history_actor CHECK (actor_type IN ('LANDLORD', 'TENANT', 'SYSTEM')),
    CONSTRAINT ck_charge_status_history_changed CHECK (from_status IS NULL OR from_status <> to_status)
);

CREATE INDEX ix_charge_status_history_charge ON charge_status_history (charge_id, created_at);
CREATE INDEX ix_charge_status_history_landlord ON charge_status_history (landlord_id);

CREATE TABLE charge_item_history (
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    landlord_id    bigint       NOT NULL,
    charge_id      bigint       NOT NULL,
    -- 항목을 지워도 이력은 남으므로 FK를 걸지 않는다
    charge_item_id bigint       NOT NULL,
    action         varchar(10)  NOT NULL,
    item_type      varchar(20)  NOT NULL,
    label          varchar(50)  NOT NULL,
    before_amount  bigint,
    after_amount   bigint,
    actor_type     varchar(10)  NOT NULL,
    actor_id       bigint,
    created_at     timestamptz  NOT NULL,
    CONSTRAINT fk_charge_item_history_landlord FOREIGN KEY (landlord_id) REFERENCES landlord (id) ON DELETE CASCADE,
    CONSTRAINT fk_charge_item_history_charge FOREIGN KEY (charge_id, landlord_id) REFERENCES charge (id, landlord_id)
        ON DELETE CASCADE,
    CONSTRAINT ck_charge_item_history_action CHECK (
        (action = 'ADD' AND before_amount IS NULL AND after_amount IS NOT NULL)
        OR (action = 'CHANGE' AND before_amount IS NOT NULL AND after_amount IS NOT NULL)
        OR (action = 'REMOVE' AND before_amount IS NOT NULL AND after_amount IS NULL)),
    CONSTRAINT ck_charge_item_history_type CHECK (item_type IN ('RENT', 'MAINTENANCE', 'ELECTRIC', 'WATER', 'GAS', 'OTHER')),
    CONSTRAINT ck_charge_item_history_actor CHECK (actor_type IN ('LANDLORD', 'SYSTEM'))
);

CREATE INDEX ix_charge_item_history_charge ON charge_item_history (charge_id, created_at);
CREATE INDEX ix_charge_item_history_landlord ON charge_item_history (landlord_id);

-- ---------------------------------------------------------------- COMMENT

COMMENT ON COLUMN lease.issue_days_before IS '납부일 며칠 전에 받을 월세(청구)를 만들지 (0~28일, 기본 7). 0이면 납부일 당일에 만든다 (M3 결정 17).';
COMMENT ON CONSTRAINT ck_lease_issue_days_before ON lease IS '청구를 만드는 시점은 납부일 0~28일 전.';

COMMENT ON TABLE charge IS '청구 = 한 계약의 한 달치 받을 월세 (PRD 4.4). 계약 하나에 같은 달은 하나(취소 포함). 금액은 항목(charge_item) 합계다. 임대인 탈퇴 파기 때 함께 지워진다.';
COMMENT ON COLUMN charge.id IS '청구 ID.';
COMMENT ON COLUMN charge.landlord_id IS '청구한 임대인 (landlord.id). 계약의 임대인과 같아야 한다(복합 FK). 모든 조회는 이 값으로 소유권을 확인한다(남의 청구는 404).';
COMMENT ON COLUMN charge.lease_id IS '청구한 계약 (lease.id).';
COMMENT ON COLUMN charge.billing_month IS '청구월 (그 달 1일, 서울 기준 날짜). 금액은 이 달 이하에서 가장 최근 월세 이력으로 정한다(M2 결정 5).';
COMMENT ON COLUMN charge.due_date IS '납부일 (서울 기준 날짜). 계약의 받는 날이 그 달에 없으면(31일, 2월 등) 그 달 마지막 날로 보정한다. 이 날이 지나도 다 받지 못하면 납부일 지남(OVERDUE)이 된다.';
COMMENT ON COLUMN charge.total_amount IS '받을 금액 합계 (원) = 항목 금액의 합. 항목을 고치면 함께 바뀐다.';
COMMENT ON COLUMN charge.paid_amount IS '받은 금액 합계 (원) = 지우지 않은 입금 기록의 합. 받을 금액보다 많을 수 있다(더 받은 금액, M3 결정 10).';
COMMENT ON COLUMN charge.status IS '청구 상태. SCHEDULED=준비 중(만들었지만 발행 전), ISSUED=받을 차례(발행함), TENANT_REPORTED=세입자가 입금했다고 알림(임대인 확인 대기), PARTIALLY_PAID=일부 받음, PAID=다 받음, OVERDUE=납부일 지남(다 받지 못함), CANCELED=취소함(끝). 전이 규칙은 ADR-023.';
COMMENT ON COLUMN charge.source IS '만든 방법. AUTO=청구 생성 배치, MANUAL=임대인이 직접 만듦.';
COMMENT ON COLUMN charge.issued_at IS '발행한 시각 (UTC). NULL=아직 발행 전(SCHEDULED). M3는 생성 직후 자동 발행한다(알림 발송은 M5).';
COMMENT ON COLUMN charge.tenant_reported_at IS '세입자가 "입금했어요"를 누른 시각 (UTC, M4 세입자 링크에서 기록). NULL=신고 없음.';
COMMENT ON COLUMN charge.paid_at IS '다 받음(PAID)이 된 시각 (UTC). 다 받음일 때만 있다. 입금을 지워 다시 다 받지 못하게 되면 NULL로 돌아간다.';
COMMENT ON COLUMN charge.canceled_at IS '취소한 시각 (UTC). 취소함(CANCELED)일 때만 있다. 취소는 되돌릴 수 없다.';
COMMENT ON COLUMN charge.cancel_reason IS '취소 사유 (예: 계약 종료로 자동 취소). 취소했을 때만 있다.';
COMMENT ON COLUMN charge.version IS '낙관적 락 번호. 바뀔 때마다 1씩 오른다. 화면이 보던 번호와 다르면 저장하지 않고 "다른 곳에서 먼저 바뀌었어요"를 안내한다(PRD 4.4.3).';
COMMENT ON COLUMN charge.created_at IS '만든 시각 (UTC, 앱의 Clock 기준).';
COMMENT ON COLUMN charge.updated_at IS '마지막 수정 시각 (UTC, 앱의 Clock 기준).';
COMMENT ON CONSTRAINT charge_pkey ON charge IS '청구 ID 기본 키.';
COMMENT ON INDEX charge_pkey IS '청구 ID 기본 키 인덱스.';
COMMENT ON CONSTRAINT fk_charge_landlord ON charge IS '청구한 임대인. 임대인이 지워지면(탈퇴 30일 뒤 파기) 청구도 지운다.';
COMMENT ON CONSTRAINT fk_charge_lease ON charge IS '같은 임대인의 계약만 가리킨다(복합 FK, ADR-018). 청구가 있는 계약은 지울 수 없다(NO ACTION).';
COMMENT ON CONSTRAINT uk_charge_lease_month ON charge IS '계약 하나에 같은 달 청구는 하나 (멱등, PRD 4.4.1). 취소한 청구도 포함하므로 취소한 달은 다시 만들 수 없다(M3 결정 8).';
COMMENT ON INDEX uk_charge_lease_month IS '같은 달 중복 청구 방지 + 계약의 청구 찾기.';
COMMENT ON CONSTRAINT uk_charge_id_landlord ON charge IS '항목·입금·이력이 (청구 ID, 임대인 ID)로 같은 임대인의 청구만 가리키게 하는 복합 FK 대상 (ADR-018).';
COMMENT ON INDEX uk_charge_id_landlord IS '복합 FK(charge_item, payment_record, 이력 테이블)가 참조하는 인덱스.';
COMMENT ON CONSTRAINT ck_charge_billing_month ON charge IS '청구월은 그 달 1일로 저장한다.';
COMMENT ON CONSTRAINT ck_charge_amounts ON charge IS '받을 금액·받은 금액은 0 이상 (PRD: paid_amount >= 0).';
COMMENT ON CONSTRAINT ck_charge_status ON charge IS '청구 상태는 7가지만.';
COMMENT ON CONSTRAINT ck_charge_source ON charge IS '만든 방법은 AUTO(배치), MANUAL(임대인)만.';
COMMENT ON CONSTRAINT ck_charge_version ON charge IS '낙관적 락 번호는 0 이상.';
COMMENT ON CONSTRAINT ck_charge_paid_at ON charge IS '다 받음(PAID)이면 다 받은 시각이 있고, 아니면 없다.';
COMMENT ON CONSTRAINT ck_charge_canceled ON charge IS '취소함(CANCELED)이면 취소 시각과 사유가 있고, 아니면 둘 다 없다.';
COMMENT ON CONSTRAINT ck_charge_issued_at ON charge IS '준비 중(SCHEDULED)이면 발행 시각이 없다. 받을 차례·세입자 신고·납부일 지남은 발행 시각이 있다(발행 뒤에만 될 수 있는 상태).';
COMMENT ON CONSTRAINT ck_charge_tenant_reported_at ON charge IS '세입자 신고(TENANT_REPORTED) 상태면 신고 시각이 있다.';
COMMENT ON INDEX ix_charge_landlord_month_status IS '임대인의 월별 청구 목록(상태 필터 포함)과 임대인 파기(CASCADE).';
COMMENT ON INDEX ix_charge_status_due_date IS '납부일 지남 배치가 상태·납부일로 대상 청구 찾기.';

COMMENT ON TABLE charge_item IS '청구 항목 (월세·관리비·공과금·기타, CHG-3). 배치는 월세·관리비(0원이면 생략) 항목을 만든다. 항목 금액은 그 청구에서만 고칠 수 있다(일할 계산 대신, M3 결정 12). 바뀐 내용은 charge_item_history에 남는다.';
COMMENT ON COLUMN charge_item.id IS '항목 ID.';
COMMENT ON COLUMN charge_item.landlord_id IS '임대인 (landlord.id). 청구의 임대인과 같아야 한다(복합 FK).';
COMMENT ON COLUMN charge_item.charge_id IS '항목이 속한 청구 (charge.id).';
COMMENT ON COLUMN charge_item.type IS '항목 종류. RENT=월세, MAINTENANCE=관리비, ELECTRIC=전기, WATER=수도, GAS=가스, OTHER=기타.';
COMMENT ON COLUMN charge_item.label IS '화면에 보일 이름 (예: 월세, 10월 전기 요금). 1~50자.';
COMMENT ON COLUMN charge_item.amount IS '금액 (원). 0 이상 1억 이하.';
COMMENT ON COLUMN charge_item.memo IS '메모. 평문이라 화면에서 "주민번호·계좌번호는 적지 마세요"라고 안내한다. NULL=없음.';
COMMENT ON COLUMN charge_item.created_at IS '추가한 시각 (UTC, 앱의 Clock 기준).';
COMMENT ON COLUMN charge_item.updated_at IS '마지막 수정 시각 (UTC, 앱의 Clock 기준).';
COMMENT ON CONSTRAINT charge_item_pkey ON charge_item IS '항목 ID 기본 키.';
COMMENT ON INDEX charge_item_pkey IS '항목 ID 기본 키 인덱스.';
COMMENT ON CONSTRAINT fk_charge_item_landlord ON charge_item IS '임대인이 지워지면(탈퇴 파기) 항목도 지운다.';
COMMENT ON CONSTRAINT fk_charge_item_charge ON charge_item IS '같은 임대인의 청구만 가리킨다(복합 FK). 청구와 함께 지워진다.';
COMMENT ON CONSTRAINT ck_charge_item_type ON charge_item IS '항목 종류는 6가지만.';
COMMENT ON CONSTRAINT ck_charge_item_label ON charge_item IS '항목 이름은 앞뒤 공백 없이 비어 있지 않아야 한다.';
COMMENT ON CONSTRAINT ck_charge_item_amount ON charge_item IS '항목 금액은 0원 이상 1억 원 이하.';
COMMENT ON INDEX ix_charge_item_charge IS '청구의 항목 목록.';
COMMENT ON INDEX ix_charge_item_landlord IS '임대인 파기(CASCADE) 때 항목 찾기.';

COMMENT ON TABLE payment_record IS '입금 기록 (PAY-1·2·4). 전액 입금도 남은 금액만큼의 기록 하나로 남긴다. 지우거나 고칠 때 행을 지우지 않고 deleted_at·사유를 남긴다(감사 기록). 정정은 원래 기록을 지운 표시 + 새 기록(replaces_id).';
COMMENT ON COLUMN payment_record.id IS '입금 기록 ID.';
COMMENT ON COLUMN payment_record.landlord_id IS '기록한 임대인 (landlord.id). 기록하는 사람은 항상 그 청구의 임대인이다.';
COMMENT ON COLUMN payment_record.charge_id IS '입금한 청구 (charge.id).';
COMMENT ON COLUMN payment_record.amount IS '받은 금액 (원). 0보다 크고 10억 이하.';
COMMENT ON COLUMN payment_record.paid_on IS '받은 날 (서울 기준 날짜). 오늘 이하, 계약 시작월 1일 이후 (M3 결정 9, 앱에서 검사).';
COMMENT ON COLUMN payment_record.source IS '기록 경로. MANUAL=임대인이 직접 입력, TENANT_REPORT=세입자 신고를 임대인이 확인해 기록(M4).';
COMMENT ON COLUMN payment_record.memo IS '메모 (예: 현금으로 받음). 평문. NULL=없음.';
COMMENT ON COLUMN payment_record.deleted_at IS '지운 시각 (UTC). NULL=유효한 기록. 지운 기록은 받은 금액 합계에서 빠지고 상세 화면에 접어서 보인다.';
COMMENT ON COLUMN payment_record.delete_reason IS '지운 사유 (필수, 예: 금액을 잘못 입력). 지웠을 때만 있다.';
COMMENT ON COLUMN payment_record.replaces_id IS '이 기록이 정정한 원래 기록 (payment_record.id, 같은 청구). NULL=정정이 아닌 새 기록.';
COMMENT ON COLUMN payment_record.created_at IS '기록한 시각 (UTC, 앱의 Clock 기준).';
COMMENT ON COLUMN payment_record.updated_at IS '마지막 수정 시각 (UTC, 앱의 Clock 기준). 지운 표시를 할 때 바뀐다.';
COMMENT ON CONSTRAINT payment_record_pkey ON payment_record IS '입금 기록 ID 기본 키.';
COMMENT ON INDEX payment_record_pkey IS '입금 기록 ID 기본 키 인덱스.';
COMMENT ON CONSTRAINT fk_payment_record_landlord ON payment_record IS '임대인이 지워지면(탈퇴 파기) 입금 기록도 지운다.';
COMMENT ON CONSTRAINT fk_payment_record_charge ON payment_record IS '같은 임대인의 청구만 가리킨다(복합 FK).';
COMMENT ON CONSTRAINT uk_payment_record_id_charge ON payment_record IS '정정 기록이 같은 청구의 원래 기록만 가리키게 하는 FK 대상.';
COMMENT ON INDEX uk_payment_record_id_charge IS 'fk_payment_record_replaces가 참조하는 인덱스.';
COMMENT ON CONSTRAINT fk_payment_record_replaces ON payment_record IS '정정한 원래 기록은 같은 청구의 기록이어야 한다.';
COMMENT ON CONSTRAINT ck_payment_record_amount ON payment_record IS '입금 금액은 0보다 크고 10억 원 이하.';
COMMENT ON CONSTRAINT ck_payment_record_source ON payment_record IS '기록 경로는 MANUAL, TENANT_REPORT만.';
COMMENT ON CONSTRAINT ck_payment_record_deleted ON payment_record IS '지운 기록은 지운 시각과 사유가 함께 있다.';
COMMENT ON CONSTRAINT ck_payment_record_memo ON payment_record IS '메모는 없거나(NULL) 비어 있지 않아야 한다.';
COMMENT ON INDEX ix_payment_record_charge_active IS '청구의 지우지 않은 입금 기록 (받은 금액 합계).';
COMMENT ON INDEX ix_payment_record_landlord IS '임대인 파기(CASCADE) 때 입금 기록 찾기.';

COMMENT ON TABLE charge_status_history IS '청구 상태 이력 (PRD 4.4.2). 상태가 바뀔 때마다 한 행(만들 때는 from_status 없음). 한 번 쓰면 고치지 않아 created_at만 둔다(consent_log와 같은 예외).';
COMMENT ON COLUMN charge_status_history.id IS '이력 ID.';
COMMENT ON COLUMN charge_status_history.landlord_id IS '임대인 (landlord.id). 청구의 임대인과 같아야 한다(복합 FK).';
COMMENT ON COLUMN charge_status_history.charge_id IS '청구 (charge.id).';
COMMENT ON COLUMN charge_status_history.from_status IS '바뀌기 전 상태. NULL=청구를 처음 만듦.';
COMMENT ON COLUMN charge_status_history.to_status IS '바뀐 뒤 상태.';
COMMENT ON COLUMN charge_status_history.actor_type IS '바꾼 주체. LANDLORD=임대인, TENANT=세입자(M4), SYSTEM=배치.';
COMMENT ON COLUMN charge_status_history.actor_id IS '바꾼 사람 ID (임대인이면 landlord.id). SYSTEM이면 NULL.';
COMMENT ON COLUMN charge_status_history.reason IS '사유 (예: 자동 발행 — 알림 발송은 M5, 납부일 지남, 입금 기록 삭제). NULL=사유 없음.';
COMMENT ON COLUMN charge_status_history.created_at IS '바뀐 시각 (UTC, 앱의 Clock 기준).';
COMMENT ON CONSTRAINT charge_status_history_pkey ON charge_status_history IS '이력 ID 기본 키.';
COMMENT ON INDEX charge_status_history_pkey IS '이력 ID 기본 키 인덱스.';
COMMENT ON CONSTRAINT fk_charge_status_history_landlord ON charge_status_history IS '임대인이 지워지면(탈퇴 파기) 이력도 지운다.';
COMMENT ON CONSTRAINT fk_charge_status_history_charge ON charge_status_history IS '같은 임대인의 청구만 가리킨다(복합 FK).';
COMMENT ON CONSTRAINT ck_charge_status_history_from ON charge_status_history IS '이전 상태는 없거나 7가지 중 하나.';
COMMENT ON CONSTRAINT ck_charge_status_history_to ON charge_status_history IS '다음 상태는 7가지 중 하나.';
COMMENT ON CONSTRAINT ck_charge_status_history_actor ON charge_status_history IS '바꾼 주체는 LANDLORD, TENANT, SYSTEM만.';
COMMENT ON CONSTRAINT ck_charge_status_history_changed ON charge_status_history IS '상태가 실제로 바뀐 경우만 기록한다(같은 상태로의 이력 금지).';
COMMENT ON INDEX ix_charge_status_history_charge IS '청구의 상태 이력 (시각 순).';
COMMENT ON INDEX ix_charge_status_history_landlord IS '임대인 파기(CASCADE) 때 이력 찾기.';

COMMENT ON TABLE charge_item_history IS '청구 항목 변경 이력 (M3 결정 12): 누가·언제 항목을 추가·변경·삭제했고 금액이 얼마에서 얼마로 바뀌었는지. 고치지 않는 기록이라 created_at만 둔다.';
COMMENT ON COLUMN charge_item_history.id IS '이력 ID.';
COMMENT ON COLUMN charge_item_history.landlord_id IS '임대인 (landlord.id). 청구의 임대인과 같아야 한다(복합 FK).';
COMMENT ON COLUMN charge_item_history.charge_id IS '청구 (charge.id).';
COMMENT ON COLUMN charge_item_history.charge_item_id IS '바뀐 항목 ID (charge_item.id). 항목을 지워도 이력은 남으므로 FK가 없다.';
COMMENT ON COLUMN charge_item_history.action IS '변경 종류. ADD=추가, CHANGE=금액·이름 변경, REMOVE=삭제.';
COMMENT ON COLUMN charge_item_history.item_type IS '항목 종류 (charge_item.type과 같은 값).';
COMMENT ON COLUMN charge_item_history.label IS '그때의 항목 이름.';
COMMENT ON COLUMN charge_item_history.before_amount IS '바뀌기 전 금액 (원). ADD면 NULL.';
COMMENT ON COLUMN charge_item_history.after_amount IS '바뀐 뒤 금액 (원). REMOVE면 NULL.';
COMMENT ON COLUMN charge_item_history.actor_type IS '바꾼 주체. LANDLORD=임대인, SYSTEM=배치(자동 생성 항목).';
COMMENT ON COLUMN charge_item_history.actor_id IS '바꾼 사람 ID (임대인이면 landlord.id). SYSTEM이면 NULL.';
COMMENT ON COLUMN charge_item_history.created_at IS '바뀐 시각 (UTC, 앱의 Clock 기준).';
COMMENT ON CONSTRAINT charge_item_history_pkey ON charge_item_history IS '이력 ID 기본 키.';
COMMENT ON INDEX charge_item_history_pkey IS '이력 ID 기본 키 인덱스.';
COMMENT ON CONSTRAINT fk_charge_item_history_landlord ON charge_item_history IS '임대인이 지워지면(탈퇴 파기) 이력도 지운다.';
COMMENT ON CONSTRAINT fk_charge_item_history_charge ON charge_item_history IS '같은 임대인의 청구만 가리킨다(복합 FK).';
COMMENT ON CONSTRAINT ck_charge_item_history_action ON charge_item_history IS 'ADD는 바뀐 뒤 금액만, CHANGE는 둘 다, REMOVE는 바뀌기 전 금액만 있다.';
COMMENT ON CONSTRAINT ck_charge_item_history_type ON charge_item_history IS '항목 종류는 6가지만.';
COMMENT ON CONSTRAINT ck_charge_item_history_actor ON charge_item_history IS '바꾼 주체는 LANDLORD, SYSTEM만 (세입자는 항목을 고치지 않는다).';
COMMENT ON INDEX ix_charge_item_history_charge IS '청구의 항목 변경 이력 (시각 순).';
COMMENT ON INDEX ix_charge_item_history_landlord IS '임대인 파기(CASCADE) 때 이력 찾기.';
