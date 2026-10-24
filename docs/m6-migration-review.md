# M6 마이그레이션 검토 (V22)

> 개발자 지시(2026-10-24): V22는 확인을 기다리지 않고 커밋하고, 이 문서에 SQL 전문과 지켜야 할 조건을 모은다.
> 검토해서 문제가 있으면 **새 마이그레이션으로** 고친다. 이미 커밋한 마이그레이션 파일은 고치지 않는다(Flyway 체크섬).

체크 표시는 작성자(Claude)가 파일과 테스트로 확인한 것이다. 개발자 검토 결과는 각 절 끝의 "검토" 줄에 적는다.

## V22 `landlord.demo_expires_at` (방문자별 임시 데모 계정, M6 커밋 4)

- [x] 기존 테이블에 NULL 허용 컬럼 하나만 더한다. 기존 행은 모두 일반 계정이라 NULL 그대로다(채울 값이 없다, UPDATE 없음)
- [x] 기본값·NOT NULL·CHECK를 두지 않는다: 기존 행 다시 쓰기가 없어 큰 테이블에서도 잠금이 짧다
- [x] 개인정보 컬럼이 아니다(지워질 시각만)
- [x] 데모 계정은 기존 임대인 파기와 같은 CASCADE로 함께 지워진다(새 FK 없음). 정리 배치 SQL은 `demo_expires_at IS NOT NULL` 조건으로 일반 계정을 지우지 않는다(`DemoAccountPurger`, 테스트 `expiredDemosAreDeletedWithTheirSessions`)
- [x] 부분 인덱스 `ix_landlord_demo_expires`(데모 계정만): 정리와 동시 상한 세기에 쓴다. 일반 계정은 인덱스에 들어가지 않는다
- [x] 새 컬럼과 인덱스에 한글 COMMENT(`SchemaCommentsTests` 통과), 시각은 UTC라고 적었다
- [x] 기존 마이그레이션(V1~V21)을 고치지 않는다
- [x] 로컬 DB(v21 → v22) 적용 확인 완료 (2026-10-24, bootRun): `Successfully applied 1 migration … now at version v22`, 앱 정상 기동. `flyway_schema_history` 마지막 줄 `22 | t`. 기존 임대인 2명은 모두 `demo_expires_at` NULL(일반 계정)
- [x] 값을 쓰는 경로 조사(코드 기준, 2026-10-24): `demo_expires_at`에 값을 넣는 곳은 `Landlord.registerDemo` 한 곳뿐이고, 이를 부르는 곳은 `LandlordService.registerDemo` ← `DemoService.start` ← `POST /demo` 하나다. 이메일 가입(`Landlord.registerLocal`)·카카오 가입(`registerKakao`)은 이 필드를 건드리지 않아 NULL이다. 컬럼이 `@Column(updatable = false)`라 계정 수정(이메일·이름·비밀번호 변경, 인증, 탈퇴, 카카오 연결 끊기)의 JPA UPDATE에는 이 컬럼이 들어가지 않는다. 앱 코드에 `landlord`를 쓰는 SQL(insert/update)은 없고, 탈퇴 복구 기능은 없다(M1-c 결정 1). 데모 정리 SQL은 지우기만 하며 `demo_expires_at IS NOT NULL` 조건이 있다. 예외는 사람이 쓰는 운영 SQL 하나(runbook 12 "데모 초기화", 이미 데모인 행만 바꿈)
- 검토: 개발자(채팅) 검토 이상 없음, V23 불필요 (2026-10-24)

```sql
-- 방문자별 임시 데모 계정 (M6 결정 3, ADR-031). 공개 주소에서 방문자가 [데모로 둘러보기]를 누르면 가짜 데이터가 든 임대인 계정을
-- 하나 만들고 24시간 뒤 지운다(임대인 파기와 같은 CASCADE). 일반 계정과 같은 테이블을 써서 소유권 검증(IDOR) 설계를 그대로 쓴다.
-- 기존 행은 모두 일반 계정이라 NULL로 둔다(채울 값이 없다).
ALTER TABLE landlord ADD COLUMN demo_expires_at timestamptz;

-- 만료된 데모 계정 찾기(매시간 정리)와 동시에 있는 데모 수 세기. 일반 계정은 인덱스에 들어가지 않는다
CREATE INDEX ix_landlord_demo_expires ON landlord (demo_expires_at) WHERE demo_expires_at IS NOT NULL;

COMMENT ON COLUMN landlord.demo_expires_at IS '임시 데모 계정이 지워질 시각 (UTC). NULL=일반 계정. 값이 있으면 방문자가 [데모로 둘러보기]로 만든 계정이고, 이 시각이 지나면 정리 배치가 계정과 모든 데이터를 지운다(CASCADE). 데모 계정은 메일을 받지 않고(인증하지 않은 가짜 주소), 계정 설정을 바꿀 수 없다.';
COMMENT ON INDEX ix_landlord_demo_expires IS '데모 계정만 담는 부분 인덱스. 만료된 데모 정리와 동시에 있는 데모 수(상한) 세기에 쓴다.';
```
