-- 로그인 유지(remember-me 30일) 토큰 (ADR-013).
-- 테이블·컬럼 이름은 Spring Security JdbcTokenRepositoryImpl의 표준을 그대로 쓴다 (그 클래스의 SQL을 재사용).
-- 표준과 다른 점: token에 원문이 아니라 SHA-256을 저장하고, last_used는 timestamptz다 (ADR-002).
CREATE TABLE persistent_logins (
    -- 기기(브라우저)마다 하나. 쿠키에 원문으로 들어간다. 로그인할 때마다 바뀌는 token과 달리 계속 유지된다
    series    varchar(64) PRIMARY KEY,
    -- landlord id (세션의 principal 이름과 같은 값)
    username  varchar(64) NOT NULL,
    -- 토큰 원문의 SHA-256 (소문자 hex 64자). 원문은 쿠키에만 있다
    token     varchar(64) NOT NULL,
    last_used timestamptz NOT NULL,
    CONSTRAINT ck_persistent_logins_token_hex CHECK (token ~ '^[0-9a-f]{64}$')
);

-- 로그아웃·비밀번호 재설정 시 그 사용자의 토큰을 모두 지울 때
CREATE INDEX ix_persistent_logins_username ON persistent_logins (username);
-- 30일 지난 토큰 정리 작업용
CREATE INDEX ix_persistent_logins_last_used ON persistent_logins (last_used);
