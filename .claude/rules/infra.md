---
paths:
  - "src/main/java/**/infra/**"
---

# infra 레이어

**기술 의존성을 격리한다.** JPA Entity, Spring Data Repository, QueryDSL, Redis,
외부 API Client 구현체가 여기 산다.

- **domain 이 선언한 인터페이스를 구현한다.** 새 인터페이스를 만들어 상위에
  노출하지 않는다
- 상위 레이어에 기술 세부사항을 노출하지 않는다. 외부 API 응답 DTO 를 그대로
  올리지 않는다
- Entity → 도메인 모델 변환을 여기서 한다
- DB 기술 변경이 service 나 domain 의 대규모 변경으로 번지지 않아야 한다
- 원칙적으로 트랜잭션을 선언하지 않는다

게이트가 검사하는 것 — `LAYER_DEPENDENCY` (다른 레이어가 infra 를 참조하면 잡힌다) ·
`REPOSITORY_INTERFACE_LIVES_IN_DOMAIN` (저장소 인터페이스를 여기 만들면 잡힌다).

상세: `docs/wiki/04-협업-규칙/아키텍처-컨벤션.md`
