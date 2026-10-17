# ADR-021: QueryDSL 도입 보류 (M2)

- 상태: 승인됨 (M3 청구 목록에서 다시 판단)
- 날짜: 2026-10-17

## 맥락
- PRD 3.1 스택에 QueryDSL이 있고, M1에서 "Hibernate 7(Boot 4.1.1 BOM: 7.4.5) 호환 여부를 먼저 검토"하기로 했다.
- 공식 자료로 확인한 사실 (2026-10-17):
  - Spring Data JPA 문서: 원본 Querydsl의 유지보수가 느려져 커뮤니티가 OpenFeign으로 포크했다(`io.github.openfeign.querydsl`). Spring Data는 이 포크를 **best-effort**로 지원한다.
  - OpenFeign Querydsl 7.0은 "JPA 3.2 + Hibernate 7.0"을 쓴다. 7.7은 Hibernate 7.4.x, Spring Boot 4.1.1로 의존성을 올렸다.
  - 원본(`com.querydsl`)은 Hibernate 7 대응을 확인하지 못했다. 쓴다면 OpenFeign 포크다.

## 결정
- **M2에서는 도입하지 않는다** (M2 결정 16).
  - M2 조회(임대인의 건물 목록과 호실 수, 건물의 호실, 호실의 계약, 계약 상세·이력)는 파생 쿼리, JPQL 한두 개, 앱 안의 집계로 충분하다. 임대인 한 명의 데이터는 5~30호실 규모다.
  - 도입하면 비용이 생긴다: 빌드에 annotation processor 설정을 넣어야 하고, best-effort로만 지원되는 포크에 기대야 한다. 생성된 Q 클래스도 관리해야 한다.
- **M3 청구 목록**(월·상태 필터, 일괄 처리)이나 **M4 대시보드**처럼 조건이 동적으로 많아질 때 다시 판단한다. 그때도 Spring Data의 `Specification`이나 JPQL로 충분한지 먼저 본다.

## 결과
- 소유권 규칙(모든 조회가 landlordId를 받음, ADR-018)은 Spring Data 파생 쿼리와 `@Query`만으로 지킨다. QueryDSL을 들이면 같은 규칙(저장소 메서드가 landlordId를 받음)을 다시 적용해야 한다.
