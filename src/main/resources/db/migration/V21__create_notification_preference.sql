-- 임대인 알림 설정 (M5 결정 13, PRD 4.5). 임대인 한 명에 한 행. 행이 없으면 모두 켬(기본값)으로 본다.
-- 계약별 설정과 보내는 시점 조정(NOTI-6)은 P1이라 두지 않는다.
CREATE TABLE notification_preference (
    landlord_id      bigint      PRIMARY KEY,
    tenant_report    boolean     NOT NULL DEFAULT true,
    overdue_digest   boolean     NOT NULL DEFAULT true,
    lease_end_digest boolean     NOT NULL DEFAULT true,
    tenant_sms       boolean     NOT NULL DEFAULT true,
    created_at       timestamptz NOT NULL,
    updated_at       timestamptz NOT NULL,
    CONSTRAINT fk_notification_preference_landlord FOREIGN KEY (landlord_id) REFERENCES landlord (id) ON DELETE CASCADE
);

COMMENT ON TABLE notification_preference IS '임대인 알림 설정 (M5 결정 13). 임대인 한 명에 한 행이고, 행이 없으면 모두 켬으로 본다. 임대인을 파기하면 함께 지워진다. 임대인 메일은 설정이 켜져 있어도 인증한 이메일이 있을 때만 보낸다.';
COMMENT ON COLUMN notification_preference.landlord_id IS '설정한 임대인 (landlord.id). 설정 화면은 로그인한 임대인의 행만 읽고 쓴다.';
COMMENT ON COLUMN notification_preference.tenant_report IS '세입자가 "입금했어요"로 알리면 임대인에게 메일로 알릴지 (시간당 1통으로 묶음). true=보냄.';
COMMENT ON COLUMN notification_preference.overdue_digest IS '납부일이 지난 받을 월세를 하루 한 번 메일로 알릴지. true=보냄.';
COMMENT ON COLUMN notification_preference.lease_end_digest IS '끝나는 날이 90·60·30일 남은 계약을 하루 한 번 메일로 알릴지. true=보냄.';
COMMENT ON COLUMN notification_preference.tenant_sms IS '이 임대인의 세입자에게 월세 안내 문자를 보낼지. true=보냄. 서비스 전체 설정(세입자 안내 문자 기능, 기본 꺼짐)이 켜져 있을 때만 실제로 나간다. 끄면 세입자 알림은 "보내지 않음(LANDLORD_OFF)"으로 남는다.';
COMMENT ON COLUMN notification_preference.created_at IS '처음 저장한 시각 (UTC, 앱의 Clock 기준).';
COMMENT ON COLUMN notification_preference.updated_at IS '마지막 수정 시각 (UTC, 앱의 Clock 기준).';
COMMENT ON CONSTRAINT notification_preference_pkey ON notification_preference IS '임대인 한 명에 설정 한 행.';
COMMENT ON INDEX notification_preference_pkey IS '임대인 ID 기본 키 인덱스.';
COMMENT ON CONSTRAINT fk_notification_preference_landlord ON notification_preference IS '설정한 임대인. 임대인을 파기하면 설정도 지운다.';
