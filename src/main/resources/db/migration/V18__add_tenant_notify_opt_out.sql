-- 세입자 알림 수신 거부 (M5 결정 9, PRD TEN-6). 알림을 하나라도 보내기 전에 만든다(M4 결정 15).
-- 링크(토큰)가 아니라 세입자에 저장한다: 알림 링크가 새 토큰으로 바뀌어도, 같은 세입자의 다른 계약에도 거부가 그대로 적용된다.
-- 참/거짓 대신 시각으로 남겨 언제 거부했는지 알 수 있게 한다.
ALTER TABLE tenant ADD COLUMN notify_opt_out_at timestamptz;

COMMENT ON COLUMN tenant.notify_opt_out_at IS '세입자가 세입자 링크 화면에서 [안내 문자 그만 받기]를 누른 시각 (UTC). NULL=안내 문자를 받음. 값이 있으면 이 세입자의 모든 계약에 세입자 알림을 보내지 않는다(SKIPPED). 세입자가 [다시 받기]를 누르면 NULL로 돌아간다. 임대인은 바꿀 수 없다.';
