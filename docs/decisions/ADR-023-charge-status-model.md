# ADR-023: 청구 상태 전이와 재계산

- 상태: 승인됨 (M3 커밋 2, M3 결정 6·7·8·10)
- 날짜: 2026-10-18

## 맥락
- 청구(받을 월세)는 입금 기록, 입금 삭제, 항목 수정, 납부일 지남, 세입자 신고, 취소로 상태가 바뀐다. 이벤트마다 "다음 상태"를 따로 정하면, 입금을 지웠을 때 어디로 돌아가야 하는지 같은 되돌림에서 규칙이 어긋나기 쉽다.
- PRD 12장 필수: PAID 취소 불가, 허용되지 않은 전이는 예외, 부분 입금 합계가 총액 이상이면 자동 PAID.

## 결정
- **상태는 사실에서 계산한다** (`Charge.expectedStatus`). 위에서부터 처음 맞는 것이 상태다.
  1. 취소했으면 CANCELED (취소는 `cancel`만 정한다. 끝 상태)
  2. 받은 금액 ≥ 총액이면 PAID (더 받아도 된다, 결정 10)
  3. 발행 전이면: 받은 돈이 있으면 PARTIALLY_PAID, 없으면 SCHEDULED
  4. 세입자 신고가 처리되지 않았으면 TENANT_REPORTED
  5. 오늘 > 납부일이면 OVERDUE (일부 받았어도 유지, 결정 7)
  6. 받은 돈이 있으면 PARTIALLY_PAID, 없으면 ISSUED
- 사실을 바꾸는 메서드(`issue`, `changeTotal`, `changePaid`, `reportByTenant`, `rejectTenantReport`, `cancel`)가 끝에서 다시 계산한다. 결과가 바뀌면 `ChargeStatus.canTransitionTo`로 검사하고, 표에 없으면 `ChargeStateException`을 던진다. 바뀐 전이는 `StatusChange`로 돌려주고, 서비스가 `charge_status_history`에 남긴다. 상태 setter는 없다.
- **전이표** (승인한 계획의 표 그대로, `ChargeStatusTest`가 49칸 모두 검사)

| 지금 \ 다음 | SCHEDULED | ISSUED | TENANT_REPORTED | PARTIALLY_PAID | PAID | OVERDUE | CANCELED |
|---|---|---|---|---|---|---|---|
| SCHEDULED | – | 발행 | ✗ | 입금 | 입금 | ✗ | 취소 |
| ISSUED | ✗ | – | 신고 | 입금 | 입금 | 납부일 지남 | 취소 |
| TENANT_REPORTED | ✗ | 신고 반려 | – | 입금 | 입금 | 신고 반려(납부일 지난 뒤) | 취소 |
| PARTIALLY_PAID | ✗ | 입금 삭제 | 나머지 신고 | – | 입금 | 납부일 지남 | ✗ (입금 먼저 삭제) |
| PAID | ✗ | 입금 삭제 | ✗ | 입금 삭제·총액 증가 | – | 입금 삭제(납부일 지난 뒤) | ✗ (PRD) |
| OVERDUE | ✗ | 납부일 수정 | 신고 | 납부일 수정 | 입금 | – | 입금 없을 때만 |
| CANCELED | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | – (끝) |

- **규칙**
  - 취소는 받은 금액이 0일 때만 한다. 사유는 필수다(DB `ck_charge_canceled`). 취소한 달은 다시 만들 수 없다(UNIQUE 전체, 결정 8).
  - 납부일 당일까지는 기다리고, **다음 날부터** 납부일 지남이다.
  - 납부일 지남 배치의 대상은 ISSUED·PARTIALLY_PAID다(`ChargeStatus.overdueCandidates`). TENANT_REPORTED는 빠진다(결정 7, PRD 4.4.2와 다름). 신고를 반려했을 때 납부일이 지났으면 그때 OVERDUE가 된다.
  - PAID에서 입금을 지워 덜 받게 됐는데 납부일이 지났으면 OVERDUE가 된다(결정 7, `ChargeTest`).
  - 발행(SCHEDULED → ISSUED)은 생성 직후 배치가 한다(결정 6). 납부일이 지난 달을 늦게 만들어도(누락 복구) 먼저 ISSUED가 되고 이어서 OVERDUE가 된다. 이력은 두 줄이다.
  - 임대인이 입금을 새로 기록하면 세입자 신고는 처리된 것으로 보고 `tenant_reported_at`을 지운다. 항목 수정으로는 지우지 않는다.
  - 발행 전 입금(SCHEDULED → PARTIALLY_PAID)은 표에 있다. 그런데 M3에서는 모든 청구를 만들자마자 발행하므로 실제로는 생기지 않는다. 그 상태에서 입금을 지우는 전이(PARTIALLY_PAID → SCHEDULED)는 표에 없어 예외가 난다. 수동 생성(M3-b)도 바로 발행해 이 경우를 만들지 않는다.
- **DB가 한 번 더 막는 것** (V16): 상태 값 7가지, PAID ⇔ paid_at, CANCELED ⇔ canceled_at ⇔ cancel_reason, SCHEDULED면 issued_at 없음, ISSUED·TENANT_REPORTED·OVERDUE면 issued_at 있음, TENANT_REPORTED면 tenant_reported_at 있음. 같은 상태로의 이력은 금지한다.
- **낙관적 락**: `charge.version`(`@Version`). 화면 폼이 보던 번호를 숨은 칸으로 보내고, 다르면 저장하지 않는다(M3-b, ADR-025).
- **엔티티 범위** (커밋 2): Charge, ChargeItem, ChargeStatusHistory, ChargeItemHistory. 입금 기록(PaymentRecord) 엔티티는 입금 기능과 함께 M3-b에서 만든다. 테이블은 V16에 이미 있다.

## 결과
- 입금 삭제, 항목 수정, 신고 반려 같은 되돌림이 "다시 계산"으로 모두 처리되어 경우마다 규칙을 따로 두지 않는다.
- 새 사실(예: M5 알림 발송 여부)을 상태에 넣으려면 `expectedStatus`의 순서와 전이표를 함께 고치고 49칸 테스트를 갱신한다.
- 모든 상태 변경에 이력이 남는다. 사유에는 개인정보를 넣지 않는다.
