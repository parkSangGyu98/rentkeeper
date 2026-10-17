-- 계정 기준 로그인 연속 실패 기록 (ADR-012). 5회 연속 실패하면 15분 잠근다.
-- 잠금은 "새 비밀번호 로그인"만 막는다. 이미 로그인된 세션과 remember-me 자동 로그인은 막지 않는다 (잠금 DoS 완화).
-- auth 모듈이 소유하는 테이블이라 landlord 테이블에 컬럼을 추가하지 않고 따로 둔다.
CREATE TABLE login_failure (
    landlord_id    bigint      PRIMARY KEY REFERENCES landlord (id) ON DELETE CASCADE,
    failed_count   smallint    NOT NULL,
    -- 이 시각까지 비밀번호 로그인 불가. NULL이면 잠기지 않음
    locked_until   timestamptz,
    last_failed_at timestamptz NOT NULL,
    created_at     timestamptz NOT NULL,
    updated_at     timestamptz NOT NULL,
    CONSTRAINT ck_login_failure_count CHECK (failed_count >= 0)
);

-- 고정 창(fixed window) 요청 카운터 (ADR-012). 로그인·가입·재설정 요청 등에 쓰고 M4 세입자 링크에도 재사용한다.
-- 몇 분~1시간이면 사라지는 값이라 created_at/updated_at을 두지 않는다.
CREATE TABLE rate_limit_bucket (
    -- 규칙 이름 + 대상. 예: 'login:ip:203.0.113.7', 'reset:email:<SHA-256>'
    bucket_key        varchar(200) PRIMARY KEY,
    window_started_at timestamptz  NOT NULL,
    hit_count         integer      NOT NULL,
    -- 창이 끝나는 시각. 이 시각이 지나면 다음 요청에서 1부터 다시 세고, 정리 작업이 지운다
    expires_at        timestamptz  NOT NULL,
    CONSTRAINT ck_rate_limit_hit_count CHECK (hit_count > 0),
    CONSTRAINT ck_rate_limit_window CHECK (expires_at > window_started_at)
);

-- 만료된 카운터 정리 작업용
CREATE INDEX ix_rate_limit_bucket_expires_at ON rate_limit_bucket (expires_at);
