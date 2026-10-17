-- 이용약관·개인정보처리방침 동의 기록 (PRD 5.1 consent_log, AUTH-6, ADR-016).
-- 한 번 쓰면 고치지 않는 기록이라 created_at/updated_at 없이 agreed_at만 둔다.
CREATE TABLE consent_log (
    id               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    landlord_id      bigint      NOT NULL REFERENCES landlord (id) ON DELETE CASCADE,
    document_type    varchar(20) NOT NULL,
    document_version varchar(20) NOT NULL,
    agreed_at        timestamptz NOT NULL,
    CONSTRAINT ck_consent_log_document_type CHECK (document_type IN ('TERMS', 'PRIVACY'))
);

-- 한 사람의 문서별 최근 동의를 찾을 때 (재동의 기능(P1)에서 "지금 버전에 동의했는지" 확인용)
CREATE INDEX ix_consent_log_landlord ON consent_log (landlord_id, document_type, agreed_at DESC);

COMMENT ON TABLE consent_log IS '이용약관·개인정보처리방침 동의 기록 (AUTH-6, ADR-016). 동의할 때마다 문서별로 한 행. 한 번 쓰면 고치지 않는다. 탈퇴 계정을 파기할 때 함께 지워진다.';
COMMENT ON COLUMN consent_log.id IS '동의 기록 ID.';
COMMENT ON COLUMN consent_log.landlord_id IS '동의한 임대인 (landlord.id). 계정이 지워지면 함께 지워진다.';
COMMENT ON COLUMN consent_log.document_type IS '동의한 문서. TERMS=이용약관, PRIVACY=개인정보처리방침.';
COMMENT ON COLUMN consent_log.document_version IS '동의한 문서의 버전 (예: v0.1). 앱의 LegalDocument에 정의된 그 시점의 버전이다.';
COMMENT ON COLUMN consent_log.agreed_at IS '동의 시각 (UTC, 앱의 Clock 기준). 가입과 같은 트랜잭션에서 기록한다.';
COMMENT ON CONSTRAINT ck_consent_log_document_type ON consent_log IS '문서 종류는 TERMS(이용약관), PRIVACY(개인정보처리방침)만 허용한다.';
COMMENT ON INDEX ix_consent_log_landlord IS '한 사람의 문서별 최근 동의 찾기 (약관 재동의 기능에서 사용 예정).';
