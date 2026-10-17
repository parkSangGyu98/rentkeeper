-- 계약 기간 겹침 방지 EXCLUDE 제약(unit_id WITH =, daterange WITH &&)에 필요하다 (PRD 5.1 lease).
-- 테이블은 각 마일스톤에서 승인받은 뒤 별도 마이그레이션으로 추가한다.
CREATE EXTENSION IF NOT EXISTS btree_gist;
