-- 아직 끊지 못한 카카오 연결 끊기(unlink) 작업 목록 (ADR-015, M1-c C안: 분리 파기).
-- 탈퇴 30일 뒤 landlord 행(이름·이메일 등)은 예정대로 지우고, 연결 끊기에 꼭 필요한 회원번호만 여기 남겨
-- 하루 한 번 파기 배치가 다시 시도한다. 성공하면 행을 바로 지운다.
-- landlord에 FK를 걸지 않는다: landlord 행이 지워진 뒤에도 남아야 하고, landlord id를 저장하지 않는다.
-- 이메일·이름·landlord id는 저장하지 않는다 (저장 항목은 아래 다섯 개뿐).

CREATE TABLE kakao_unlink_pending (
    kakao_id          varchar(64) PRIMARY KEY,
    withdrawn_at      timestamptz NOT NULL,
    failure_count     integer     NOT NULL DEFAULT 0,
    last_failed_at    timestamptz,
    last_failure_type varchar(20),
    CONSTRAINT ck_kakao_unlink_pending_kakao_id_digits CHECK (kakao_id ~ '^[0-9]+$'),
    CONSTRAINT ck_kakao_unlink_pending_failure_count CHECK (failure_count >= 0),
    CONSTRAINT ck_kakao_unlink_pending_failure_type
        CHECK (last_failure_type IN ('CONFIG', 'TRANSIENT', 'REJECTED')),
    CONSTRAINT ck_kakao_unlink_pending_failure_fields CHECK (
        (failure_count = 0 AND last_failed_at IS NULL AND last_failure_type IS NULL)
        OR (failure_count > 0 AND last_failed_at IS NOT NULL AND last_failure_type IS NOT NULL))
);

COMMENT ON TABLE kakao_unlink_pending IS '아직 끊지 못한 카카오 연결 끊기 작업 목록 (ADR-015). 탈퇴 때 연결 끊기에 실패한 회원번호만 남기고, 파기 배치가 하루 한 번 다시 시도한다. 성공하면 행을 바로 지운다. landlord가 지워진 뒤에도 남아야 하므로 landlord에 FK가 없다. 이메일·이름·landlord id는 저장하지 않는다. 같은 회원번호의 사용 중(ACTIVE) 계정이 있으면 끊지 않고 행을 지운다(다시 동의해 쓰는 연결이므로).';
COMMENT ON COLUMN kakao_unlink_pending.kakao_id IS '연결을 끊을 카카오 회원번호 (개인정보, 끊기에 성공하면 행째 삭제). 같은 회원번호는 한 행만(PK): 탈퇴-재가입-탈퇴를 반복해도 하나로 합친다.';
COMMENT ON COLUMN kakao_unlink_pending.withdrawn_at IS '탈퇴 시각 (UTC). 같은 회원번호가 다시 들어오면 더 이른 값을 유지한다. 이 시각부터 60일이 지나도 못 끊으면 "심각" 알림.';
COMMENT ON COLUMN kakao_unlink_pending.failure_count IS '연속 실패 횟수. 성공하면 행이 지워지므로 항상 연속 실패 횟수다. 0=아직 재시도 전(파기 때 미리 만들어 둔 행).';
COMMENT ON COLUMN kakao_unlink_pending.last_failed_at IS '최근 실패 시각 (UTC). NULL=아직 실패 기록 없음(failure_count=0).';
COMMENT ON COLUMN kakao_unlink_pending.last_failure_type IS '최근 실패 종류. CONFIG=401/403 설정 오류(어드민 키·허용 IP, 첫 실패에 알림), TRANSIENT=5xx·429·시간 초과·연결 실패(연속 3회부터 알림), REJECTED=그 밖의 4xx(첫 실패에 알림, 이미 끊긴 회원일 때의 응답이 여기 들어갈 것으로 보지만 확인 전). NULL=아직 실패 기록 없음.';

COMMENT ON INDEX kakao_unlink_pending_pkey IS '같은 카카오 회원번호의 대기 행 중복 방지 (한 회원번호에 한 행).';
COMMENT ON CONSTRAINT ck_kakao_unlink_pending_kakao_id_digits ON kakao_unlink_pending IS '카카오 회원번호는 숫자로만 이루어진다.';
COMMENT ON CONSTRAINT ck_kakao_unlink_pending_failure_count ON kakao_unlink_pending IS '실패 횟수는 0 이상.';
COMMENT ON CONSTRAINT ck_kakao_unlink_pending_failure_type ON kakao_unlink_pending IS '실패 종류는 CONFIG(설정 오류), TRANSIENT(일시 장애), REJECTED(그 밖의 거부)만.';
COMMENT ON CONSTRAINT ck_kakao_unlink_pending_failure_fields ON kakao_unlink_pending IS '실패 횟수가 0이면 실패 시각·종류가 없고, 1 이상이면 둘 다 있다.';

-- V10 컬럼의 뜻이 바뀌었다: 재시도 대상은 이제 대기 테이블로만 정한다. V10 파일은 고치지 않고 설명만 다시 붙인다
COMMENT ON COLUMN landlord.kakao_unlinked_at IS '이 탈퇴 건의 카카오 연결 끊기가 끝난 시각 (UTC). 탈퇴한 카카오 계정에만 쓴다. 이메일 계정·사용 중인 계정은 항상 NULL. 끊기에 성공하면 같은 회원번호의 탈퇴 계정 행 모두에 채운다. 탈퇴한 카카오 계정인데 NULL이면 아직 못 끊은 것이다: 재시도는 kakao_unlink_pending 행으로만 하고, 파기할 때 이 값이 NULL이면 대기 행이 있도록 보장한 뒤 지운다.';
