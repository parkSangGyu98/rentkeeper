-- 카카오 로그인(AUTH-2)과 회원 탈퇴(AUTH-5)를 위한 컬럼·제약 (ADR-014, ADR-015).
-- 탈퇴 후 30일 유예 중에도 같은 이메일·카카오 계정으로 새로 가입할 수 있게(M1-c 결정 1: B안),
-- 중복 방지 unique 인덱스를 "사용 중(ACTIVE)인 계정끼리만"으로 바꾼다.

ALTER TABLE landlord ADD COLUMN kakao_id varchar(64);
ALTER TABLE landlord ADD COLUMN withdrawn_at timestamptz;

ALTER TABLE landlord ADD CONSTRAINT ck_landlord_kakao_id_digits CHECK (kakao_id ~ '^[0-9]+$');
ALTER TABLE landlord ADD CONSTRAINT ck_landlord_kakao_credentials
    CHECK (auth_provider <> 'KAKAO' OR (kakao_id IS NOT NULL AND password_hash IS NULL));
ALTER TABLE landlord ADD CONSTRAINT ck_landlord_local_no_kakao CHECK (auth_provider <> 'LOCAL' OR kakao_id IS NULL);
ALTER TABLE landlord ADD CONSTRAINT ck_landlord_withdrawn CHECK ((status = 'WITHDRAWN') = (withdrawn_at IS NOT NULL));

-- 카카오 회원번호 중복 방지: 사용 중인 카카오 계정끼리만
CREATE UNIQUE INDEX uk_landlord_kakao_id ON landlord (kakao_id) WHERE auth_provider = 'KAKAO' AND status = 'ACTIVE';

-- 이메일 중복 방지를 "사용 중인 이메일 가입 계정끼리만"으로 바꾼다 (V2의 인덱스를 지우고 다시 만든다).
-- 인덱스를 지우면 V7에서 붙인 COMMENT도 함께 사라지므로 아래에서 다시 붙인다.
DROP INDEX uk_landlord_local_email;
CREATE UNIQUE INDEX uk_landlord_local_email ON landlord (email) WHERE auth_provider = 'LOCAL' AND status = 'ACTIVE';

-- 파기 배치가 "탈퇴 후 30일 지난 계정"을 찾을 때
CREATE INDEX ix_landlord_withdrawn_at ON landlord (withdrawn_at) WHERE status = 'WITHDRAWN';

COMMENT ON COLUMN landlord.kakao_id IS '카카오 회원번호 (숫자 문자열, 앱별 사용자 ID). 카카오 계정만 있고 이메일 가입 계정은 NULL. 카카오 정책상 개인정보라 탈퇴 파기 때 행과 함께 지운다.';
COMMENT ON COLUMN landlord.withdrawn_at IS '탈퇴 처리 시각 (UTC). NULL=탈퇴 안 함. 이 시각부터 30일 뒤 파기 배치가 계정을 완전히 지운다.';

COMMENT ON CONSTRAINT ck_landlord_kakao_id_digits ON landlord IS '카카오 회원번호는 숫자로만 이루어진다.';
COMMENT ON CONSTRAINT ck_landlord_kakao_credentials ON landlord IS '카카오 계정은 회원번호가 반드시 있고 비밀번호는 없다.';
COMMENT ON CONSTRAINT ck_landlord_local_no_kakao ON landlord IS '이메일 가입 계정에는 카카오 회원번호가 없다 (계정 자동 연결·병합 안 함).';
COMMENT ON CONSTRAINT ck_landlord_withdrawn ON landlord IS '탈퇴 상태(WITHDRAWN)와 탈퇴 시각은 항상 함께 있다.';

COMMENT ON INDEX uk_landlord_kakao_id IS '카카오 회원번호 중복 방지. 사용 중(ACTIVE)인 카카오 계정끼리만 적용한다. 탈퇴 유예 중에도 같은 카카오 계정으로 새로 가입할 수 있다(ADR-015).';
COMMENT ON INDEX uk_landlord_local_email IS '이메일 중복 방지. 사용 중(ACTIVE)인 이메일 가입(LOCAL) 계정끼리만 적용한다. 카카오 계정과 탈퇴 유예 중인 계정은 같은 이메일이어도 허용(ADR-006, ADR-015).';
COMMENT ON INDEX ix_landlord_withdrawn_at IS '파기 배치가 탈퇴 후 30일 지난 계정을 찾을 때.';
