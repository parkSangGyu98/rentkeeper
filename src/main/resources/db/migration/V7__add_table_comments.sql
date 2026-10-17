-- 기존 테이블(V2~V6)에 한글 설명(COMMENT)을 붙인다. DBeaver 등 DB 도구에서 바로 보이게 하기 위해서다.
-- SQL 파일의 "--" 주석은 DB에 저장되지 않는다. 기존 마이그레이션 파일은 고치지 않는다(Flyway 체크섬).
-- 근거: PRD 5장, ADR-005·006·011·012·013, 각 마이그레이션 SQL, Spring Session 4.1.1 소스(JdbcIndexedSessionRepository).

-- =====================================================================
-- landlord (V2, V4)
-- =====================================================================
COMMENT ON TABLE landlord IS '임대인 계정. 서비스에 가입한 고객 한 명이 한 행이다 (PRD 5.1).';
COMMENT ON COLUMN landlord.id IS '임대인 ID. 세션(spring_session.principal_name)과 로그인 유지(persistent_logins.username)에도 이 값이 문자열로 들어간다.';
COMMENT ON COLUMN landlord.email IS '연락·알림용 이메일. 이메일 가입(LOCAL) 계정은 로그인 ID 겸용. 소문자로 정규화해 저장한다(ADR-006). 카카오 계정은 NULL일 수 있다(M1-c).';
COMMENT ON COLUMN landlord.password_hash IS '비밀번호 해시. "{bcrypt}$2a$12$..." 형식(BCrypt cost 12). 평문은 저장하지 않는다. 카카오 계정은 NULL(M1-c).';
COMMENT ON COLUMN landlord.auth_provider IS '가입 방식. LOCAL=이메일+비밀번호, KAKAO=카카오 로그인(M1-c). 같은 이메일이어도 방식이 다르면 별도 계정이다(자동 병합 안 함).';
COMMENT ON COLUMN landlord.name IS '화면에 부를 이름(호칭). 예: 김임대, 행복빌라 사장님. 1~50자.';
COMMENT ON COLUMN landlord.status IS '계정 상태. ACTIVE=사용 중, WITHDRAWN=탈퇴 처리됨(30일 뒤 개인정보 파기 대상, M1-c).';
COMMENT ON COLUMN landlord.plan IS '요금제. FREE=무료(기본값), PRO=유료(Phase 3).';
COMMENT ON COLUMN landlord.created_at IS '가입 시각 (UTC로 저장, 앱의 Clock 기준).';
COMMENT ON COLUMN landlord.updated_at IS '마지막 수정 시각 (UTC로 저장, 앱의 Clock 기준).';
COMMENT ON COLUMN landlord.email_verified_at IS '이메일 인증 시각 (UTC). NULL=미인증. 알림은 인증된 주소로만 보낸다. 이메일을 바꾸면 새 주소 확인 시각으로 바뀐다.';

COMMENT ON CONSTRAINT ck_landlord_email_normalized ON landlord IS '이메일은 앞뒤 공백 없는 소문자로만 저장한다. 대소문자만 다른 주소로 중복 가입되는 것을 막는다(ADR-006).';
COMMENT ON CONSTRAINT ck_landlord_local_credentials ON landlord IS '이메일 가입(LOCAL) 계정은 이메일과 비밀번호 해시가 반드시 있어야 한다.';
COMMENT ON INDEX uk_landlord_local_email IS '이메일 중복 방지. 이메일 가입(LOCAL) 계정끼리만 적용한다. 카카오 계정은 같은 이메일이어도 허용(자동 병합 안 함, ADR-006).';

-- =====================================================================
-- spring_session, spring_session_attributes (V3, Spring Session 표준 테이블)
-- =====================================================================
COMMENT ON TABLE spring_session IS '로그인 세션 (Spring Session JDBC 표준 테이블, ADR-005). 앱을 재시작해도 로그인이 유지된다. 만료된 행은 Spring Session이 1분마다 지운다.';
COMMENT ON COLUMN spring_session.primary_id IS '세션 행의 내부 ID (무작위 UUID). 세션 ID가 바뀌어도 그대로다. 속성 테이블이 이 값을 참조한다.';
COMMENT ON COLUMN spring_session.session_id IS '쿠키(SESSION)에 들어가는 세션 ID. 로그인할 때 바뀐다(세션 고정 공격 방지).';
COMMENT ON COLUMN spring_session.creation_time IS '세션 생성 시각. Unix epoch 밀리초(UTC 기준).';
COMMENT ON COLUMN spring_session.last_access_time IS '마지막 요청 시각. Unix epoch 밀리초(UTC 기준).';
COMMENT ON COLUMN spring_session.max_inactive_interval IS '유휴 타임아웃(초). 7200=2시간. 이 시간 동안 요청이 없으면 만료된다.';
COMMENT ON COLUMN spring_session.expiry_time IS '만료 예정 시각 = 마지막 요청 시각 + 유휴 타임아웃. Unix epoch 밀리초(UTC 기준).';
COMMENT ON COLUMN spring_session.principal_name IS '로그인한 임대인 ID(landlord.id, 문자열). NULL=로그인 전 세션. 비밀번호 변경·재설정 때 이 값으로 사용자의 세션을 모두 찾아 지운다.';
COMMENT ON INDEX spring_session_ix1 IS '쿠키의 세션 ID로 세션을 찾는다 (unique).';
COMMENT ON INDEX spring_session_ix2 IS '만료된 세션 정리용.';
COMMENT ON INDEX spring_session_ix3 IS '한 사용자의 세션을 모두 찾을 때 (비밀번호 변경·재설정 시 로그아웃).';

COMMENT ON TABLE spring_session_attributes IS '세션에 담긴 값 (Spring Session JDBC 표준 테이블). 로그인 정보, CSRF 토큰 등이 한 행씩 들어간다.';
COMMENT ON COLUMN spring_session_attributes.session_primary_id IS '어느 세션의 값인지 (spring_session.primary_id). 세션이 지워지면 함께 지워진다.';
COMMENT ON COLUMN spring_session_attributes.attribute_name IS '속성 이름. 예: SPRING_SECURITY_CONTEXT(로그인 정보), CSRF 토큰.';
COMMENT ON COLUMN spring_session_attributes.attribute_bytes IS '속성 값을 Java 직렬화한 바이트. 사람이 읽을 수 없다. 로그인 정보 클래스 구조를 바꾸면 기존 값을 못 읽을 수 있다(ADR-005).';

-- =====================================================================
-- account_token (V4)
-- =====================================================================
COMMENT ON TABLE account_token IS '1회용 계정 토큰: 이메일 인증, 이메일 변경, 비밀번호 재설정 링크 (ADR-011). 원문은 메일 링크에만 있고 여기에는 해시만 저장한다.';
COMMENT ON COLUMN account_token.id IS '토큰 ID.';
COMMENT ON COLUMN account_token.landlord_id IS '토큰을 받은 임대인 (landlord.id). 계정이 지워지면 함께 지워진다.';
COMMENT ON COLUMN account_token.purpose IS '용도. EMAIL_VERIFICATION=가입 이메일 인증(24시간), EMAIL_CHANGE=새 이메일 확인 후 변경(24시간), PASSWORD_RESET=비밀번호 재설정(30분).';
COMMENT ON COLUMN account_token.token_hash IS '토큰 원문의 SHA-256 (소문자 hex 64자). DB가 유출돼도 링크를 만들 수 없게 원문 대신 저장한다.';
COMMENT ON COLUMN account_token.target_email IS '인증할 이메일 주소. 이메일 인증·변경 토큰에만 있고 재설정 토큰은 NULL. 메일을 보낸 뒤 계정 이메일이 바뀌면 예전 링크로는 인증되지 않는다.';
COMMENT ON COLUMN account_token.expires_at IS '만료 시각 (UTC). 이 시각부터는 쓸 수 없다.';
COMMENT ON COLUMN account_token.used_at IS '사용했거나 새 토큰 발급으로 무효가 된 시각 (UTC). NULL=아직 쓸 수 있음(1회용).';
COMMENT ON COLUMN account_token.created_at IS '발급 시각 (UTC).';
COMMENT ON COLUMN account_token.updated_at IS '마지막 수정 시각 (UTC). 사용·무효 처리 때 바뀐다.';

COMMENT ON CONSTRAINT uk_account_token_hash ON account_token IS '받은 링크의 토큰을 해시로 바로 찾기 위한 unique 제약.';
COMMENT ON CONSTRAINT ck_account_token_hash_hex ON account_token IS '해시만 저장되는지 확인한다 (원문이 실수로 저장되는 것을 막음).';
COMMENT ON CONSTRAINT ck_account_token_target_email ON account_token IS '이메일 인증·변경 토큰은 대상 주소가 반드시 있고, 재설정 토큰은 없어야 한다.';
COMMENT ON CONSTRAINT ck_account_token_email_normalized ON account_token IS '대상 이메일도 계정 이메일과 같은 방식(소문자, 앞뒤 공백 없음)으로 저장한다.';
COMMENT ON INDEX ix_account_token_active IS '같은 계정·용도의 아직 쓸 수 있는 토큰 찾기 (새 링크를 보내면 이전 링크를 무효로 만들 때).';
COMMENT ON INDEX ix_account_token_expires_at IS '만료 토큰 정리 작업용 (매일 04:00 KST, 만료 후 7일 지난 행 삭제).';

-- =====================================================================
-- login_failure (V5)
-- =====================================================================
COMMENT ON TABLE login_failure IS '계정별 비밀번호 연속 실패 기록 (ADR-012). 5번 연속 틀리면 15분 동안 비밀번호 로그인을 막는다. 로그인 성공·비밀번호 재설정 시 행을 지운다.';
COMMENT ON COLUMN login_failure.landlord_id IS '임대인 (landlord.id). 계정이 지워지면 함께 지워진다.';
COMMENT ON COLUMN login_failure.failed_count IS '연속 실패 횟수. 5가 되면 잠긴다. 잠긴 동안의 실패는 세지 않고, 잠금이 끝나면 0부터 다시 센다. 비밀번호 변경·이메일 변경 화면에서 지금 비밀번호를 틀려도 더한다.';
COMMENT ON COLUMN login_failure.locked_until IS '이 시각(UTC)까지 비밀번호 로그인 불가. NULL=잠기지 않음. 이미 로그인된 세션과 로그인 유지(remember-me)는 막지 않는다(잠금 악용 피해를 줄이기 위해).';
COMMENT ON COLUMN login_failure.last_failed_at IS '마지막으로 비밀번호를 틀린 시각 (UTC).';
COMMENT ON COLUMN login_failure.created_at IS '처음 실패한 시각 (UTC).';
COMMENT ON COLUMN login_failure.updated_at IS '마지막 수정 시각 (UTC).';

-- =====================================================================
-- rate_limit_bucket (V5)
-- =====================================================================
COMMENT ON TABLE rate_limit_bucket IS '요청 횟수 제한 카운터, 고정 창 방식 (ADR-012). 창이 끝난 행은 10분마다 지운다. 여러 서버가 같은 한도를 공유하도록 DB에 둔다.';
COMMENT ON COLUMN rate_limit_bucket.bucket_key IS '규칙 이름 + 대상. 예: login:ip:203.0.113.7(로그인, IP별), reset-request:email:<SHA-256>(재설정 요청, 이메일별). 이메일은 원문 대신 해시를 쓴다.';
COMMENT ON COLUMN rate_limit_bucket.window_started_at IS '지금 창이 시작된 시각 (UTC).';
COMMENT ON COLUMN rate_limit_bucket.hit_count IS '이번 창에서 들어온 요청 수 (한도를 넘긴 요청도 센다). 규칙의 한도보다 크면 거절한다.';
COMMENT ON COLUMN rate_limit_bucket.expires_at IS '창이 끝나는 시각 (UTC). 이 시각이 지나면 다음 요청부터 1로 다시 센다.';
COMMENT ON CONSTRAINT ck_rate_limit_window ON rate_limit_bucket IS '창의 끝은 시작보다 뒤여야 한다.';
COMMENT ON INDEX ix_rate_limit_bucket_expires_at IS '창이 끝난 카운터 정리 작업용 (10분마다). 키에 IP가 들어 있어 오래 남기지 않는다.';

-- =====================================================================
-- persistent_logins (V6, Spring Security 표준 테이블 이름)
-- =====================================================================
COMMENT ON TABLE persistent_logins IS '로그인 유지(remember-me 30일) 토큰 (ADR-013). 기기(브라우저)마다 한 행. 로그아웃·비밀번호 재설정·변경 시 그 사용자의 행을 모두 지운다. 30일 지난 행은 매일 04:10 KST에 지운다.';
COMMENT ON COLUMN persistent_logins.series IS '기기 식별값. 쿠키에 원문으로 들어가고, 자동 로그인할 때마다 바뀌는 token과 달리 계속 유지된다.';
COMMENT ON COLUMN persistent_logins.username IS '임대인 ID(landlord.id, 문자열). 이메일이 아니다(이메일은 바뀔 수 있으므로).';
COMMENT ON COLUMN persistent_logins.token IS '토큰 원문의 SHA-256 (소문자 hex 64자). 자동 로그인할 때마다 새 값으로 바뀐다. 이미 쓴 토큰이 다시 오면 도난으로 보고 이 사용자의 행을 모두 지운다.';
COMMENT ON COLUMN persistent_logins.last_used IS '마지막으로 쓰인 시각 (UTC). 30일이 지나면 만료다.';
COMMENT ON CONSTRAINT ck_persistent_logins_token_hex ON persistent_logins IS '해시만 저장되는지 확인한다 (원문이 실수로 저장되는 것을 막음).';
COMMENT ON INDEX ix_persistent_logins_username IS '한 사용자의 토큰을 모두 지울 때 (로그아웃·비밀번호 재설정·변경).';
COMMENT ON INDEX ix_persistent_logins_last_used IS '30일 지난 토큰 정리 작업용.';
