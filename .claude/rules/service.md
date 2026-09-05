---
paths:
  - "src/main/java/**/service/**"
---

# service 레이어

비즈니스 로직을 **직접 구현하는 곳이 아니라 비즈니스 흐름을 표현하는 곳**이다.
신규 입사자·기획자·운영 담당자가 메서드를 읽고 업무 흐름을 이해할 수 있어야 한다.

허용 — 유스케이스 public 메서드, 트랜잭션 경계, 도메인 서비스와 도메인 모델을
조합한 흐름, **infra 저장소를 직접 주입받은** 조회·저장.

금지 —

- QueryDSL `JPAQueryFactory` · `EntityManager` · Redis · Kafka · HTTP Client 직접 사용.
  복잡한 조회는 infra 저장소의 메서드로 만든다
- `Page` · `Slice` · `Pageable` · `Example` 을 **public 시그니처에 노출**하기. 페이징
  조건은 커맨드로 받고 결과는 결과 객체로 반환한다. 메서드 안에서 쓰는 것은 허용
- 요청 DTO 를 그대로 인자로 받기 (Command·Query 객체로 변환해서 받는다)
- 응답 DTO 를 여기서 조립하기
- 복잡한 if·for·switch 가 누적되어 구현 상세가 드러나는 코드

트랜잭션 경계는 **public 메서드**에 둔다. 조회 전용은 `@Transactional(readOnly = true)`.
하위에서 독립 트랜잭션이 필요하면 이유를 PR 에 적는다.

게이트가 검사하는 것 — `LAYER_DEPENDENCY` (presentation 을 참조하면 잡힌다).
**infra 참조는 허용된 방향이다.**

`@Transactional` 위치와 Spring Data 타입 노출 금지는 **아직 게이트에 없다.**
클래스패스 문제는 STAR-29 로 풀렸으니 이제 위반 픽스처를 만들 수 있다.
그때까지는 사람 리뷰가 본다.

상세: `docs/wiki/04-협업-규칙/아키텍처-컨벤션.md`
