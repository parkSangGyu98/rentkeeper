-- 실제 카카오로 확인한 결과를 설명에 반영한다 (ADR-015). 스키마는 바꾸지 않는다. V11 파일은 고치지 않는다.
-- 이미 끊긴 회원에게 연결 끊기를 다시 요청하면 400 + 카카오 에러 코드 -101이 온다. 이 응답은 성공으로 처리하므로
-- 대기 행에 REJECTED로 남지 않는다.

COMMENT ON COLUMN kakao_unlink_pending.last_failure_type IS '최근 실패 종류. CONFIG=401/403 설정 오류(어드민 키·허용 IP, 첫 실패에 알림), TRANSIENT=5xx·429·시간 초과·연결 실패(연속 3회부터 알림), REJECTED=그 밖의 4xx(첫 실패에 알림). 400이면서 카카오 에러 코드가 -101(앱과 연결되지 않은 사용자: 이미 끊김 또는 연결된 적 없음)이면 실패가 아니라 성공으로 처리해 행을 지운다. NULL=아직 실패 기록 없음.';

-- V11에서는 같은 이름의 인덱스에만 설명을 붙였다. PK 제약에도 붙인다
COMMENT ON CONSTRAINT kakao_unlink_pending_pkey ON kakao_unlink_pending IS '같은 카카오 회원번호의 대기 행 중복 방지 (한 회원번호에 한 행). 탈퇴-재가입-탈퇴를 반복해도 하나로 합친다.';
