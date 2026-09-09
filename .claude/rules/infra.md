---
paths:
  - "src/main/java/**/infra/**"
---

# infra 레이어

**기술 의존성을 격리한다.** JPA Entity, Spring Data Repository, QueryDSL, Redis,
외부 API Client 구현체가 여기 산다.

- **저장소 인터페이스를 여기 선언하고 Spring Data 를 상속한다.** domain 에 포트를
  두고 여기서 구현하는 방식은 아키텍처 컨벤션이 기각했다 — 얻는 것은 DB 기술
  교체의 격리 하나인데, 도메인 모델과 Entity 를 따로 두는 변환 비용을 계속 치른다
- 상위 레이어에 기술 세부사항을 노출하지 않는다. 외부 API 응답 DTO 를 그대로
  올리지 않는다
- **Entity 가 곧 도메인 모델이다.** 변환 계층을 두지 않는다. Entity 는 domain 에 산다
- DB 기술 변경이 service 나 domain 의 대규모 변경으로 번지지 않아야 한다
- 원칙적으로 트랜잭션을 선언하지 않는다
- **마이그레이션 번호는 영역별 대역에서 고른다.** Catalog `V1`~`V9` · Identity
  `V10`~`V19` · Companion `V20`~`V29` · Comment/Safety `V30`~`V39`. 대역을 안 지키면
  머지에서 파일명이 충돌하고, 이름을 바꾸는 순간 이미 적용된 쪽의 체크섬이 깨진다.
  고르기 전에 `ls src/main/resources/db/migration/ | sort -V` 로 실제 파일을 본다
  (`docs/wiki/03-운영-인프라/DB-마이그레이션-규칙.md`)

게이트가 검사하는 것 — `LAYER_DEPENDENCY` (**service 말고** 다른 레이어가 infra 를
참조하면 잡힌다) · `REPOSITORY_INTERFACE_LIVES_IN_INFRA` (저장소 인터페이스를 여기
밖에 만들면 잡힌다).

상세: `docs/wiki/04-협업-규칙/아키텍처-컨벤션.md`
