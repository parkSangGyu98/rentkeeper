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
- [ ] 로컬 DB(v21 → v22)에 적용해 실패가 없는지: 다음 bootRun 때 확인한다 (`SELECT version, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1;`)
- 검토:

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
