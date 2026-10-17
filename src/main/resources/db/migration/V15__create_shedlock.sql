-- 배치 중복 실행 방지 잠금 (ShedLock, ADR-022). 서버가 여러 대이거나 같은 작업이 겹쳐 시작돼도 한 번만 실행되게 한다.
-- 테이블 구조는 ShedLock 공식 문서의 PostgreSQL DDL 그대로다(라이브러리가 이 컬럼 이름과 타입을 쓴다).
-- 시각은 DB 서버 시각 기준 UTC로 기록된다(usingDbTime). 업무 날짜(서울)와는 쓰지 않는다.
-- 임대인 소유 데이터가 아니라 landlord_id가 없다.

CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);

COMMENT ON TABLE shedlock IS '배치 작업 잠금 (ShedLock 라이브러리, ADR-022). 작업 이름마다 한 행. 잠금이 풀리는 시각 전에는 같은 작업이 다시 시작되지 않는다. 앱이 직접 읽고 쓰지 않는다.';
COMMENT ON COLUMN shedlock.name IS '작업 이름 (예: charge-generation, charge-overdue, withdrawn-account-purge).';
COMMENT ON COLUMN shedlock.lock_until IS '이 시각까지 잠금 유지 (UTC, DB 서버 시각, 시간대 없는 timestamp). 작업이 끝나면 lockAtLeastFor만큼 남기고 당겨진다. 서버가 죽어도 lockAtMostFor 뒤에는 풀린다.';
COMMENT ON COLUMN shedlock.locked_at IS '잠금을 잡은 시각 (UTC, DB 서버 시각).';
COMMENT ON COLUMN shedlock.locked_by IS '잠금을 잡은 서버 이름(호스트명). 장애 때 어느 서버가 돌렸는지 확인용.';
COMMENT ON CONSTRAINT shedlock_pkey ON shedlock IS '작업 이름마다 잠금 한 행.';
COMMENT ON INDEX shedlock_pkey IS '작업 이름으로 잠금 찾기.';
