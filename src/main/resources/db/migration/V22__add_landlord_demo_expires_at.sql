-- 방문자별 임시 데모 계정 (M6 결정 3, ADR-031). 공개 주소에서 방문자가 [데모로 둘러보기]를 누르면 가짜 데이터가 든 임대인 계정을
-- 하나 만들고 24시간 뒤 지운다(임대인 파기와 같은 CASCADE). 일반 계정과 같은 테이블을 써서 소유권 검증(IDOR) 설계를 그대로 쓴다.
-- 기존 행은 모두 일반 계정이라 NULL로 둔다(채울 값이 없다).
ALTER TABLE landlord ADD COLUMN demo_expires_at timestamptz;

-- 만료된 데모 계정 찾기(매시간 정리)와 동시에 있는 데모 수 세기. 일반 계정은 인덱스에 들어가지 않는다
CREATE INDEX ix_landlord_demo_expires ON landlord (demo_expires_at) WHERE demo_expires_at IS NOT NULL;

COMMENT ON COLUMN landlord.demo_expires_at IS '임시 데모 계정이 지워질 시각 (UTC). NULL=일반 계정. 값이 있으면 방문자가 [데모로 둘러보기]로 만든 계정이고, 이 시각이 지나면 정리 배치가 계정과 모든 데이터를 지운다(CASCADE). 데모 계정은 메일을 받지 않고(인증하지 않은 가짜 주소), 계정 설정을 바꿀 수 없다.';
COMMENT ON INDEX ix_landlord_demo_expires IS '데모 계정만 담는 부분 인덱스. 만료된 데모 정리와 동시에 있는 데모 수(상한) 세기에 쓴다.';
