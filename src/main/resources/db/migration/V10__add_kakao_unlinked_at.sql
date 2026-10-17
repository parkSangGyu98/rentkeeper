-- 탈퇴한 카카오 계정의 "카카오 연결 끊기(unlink)" 성공 여부 (ADR-015).
-- 탈퇴 때 연결 끊기가 실패하면 이 값이 NULL로 남고, 파기 배치가 다시 시도한다.
-- kakao_id는 파기 전까지 지울 수 없으므로(ck_landlord_kakao_credentials) 성공 여부를 따로 기록한다.

ALTER TABLE landlord ADD COLUMN kakao_unlinked_at timestamptz;

-- 연결 끊기는 탈퇴한 카카오 계정에만 한다. 이메일(LOCAL) 계정이나 사용 중인 계정에 값이 있으면 잘못된 데이터다
ALTER TABLE landlord ADD CONSTRAINT ck_landlord_kakao_unlinked
    CHECK (kakao_unlinked_at IS NULL OR (auth_provider = 'KAKAO' AND status = 'WITHDRAWN'));

COMMENT ON COLUMN landlord.kakao_unlinked_at IS '카카오 연결 끊기(unlink) 성공 시각 (UTC). 탈퇴한 카카오 계정에만 쓴다. NULL=연결 끊기를 아직 못 함(이메일 계정·사용 중인 계정은 항상 NULL). 탈퇴한 카카오 계정인데 NULL이면 파기 배치가 다시 시도한다.';

COMMENT ON CONSTRAINT ck_landlord_kakao_unlinked ON landlord IS '카카오 연결 끊기 시각은 탈퇴한(WITHDRAWN) 카카오 계정에만 있을 수 있다. 이메일(LOCAL) 계정은 항상 NULL.';
