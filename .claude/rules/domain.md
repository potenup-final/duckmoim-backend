---
paths:
  - "src/main/java/**/domain/**"
---

# domain 레이어

**의존 그래프의 종착점이다.** 어떤 레이어도 참조하지 않는다. Spring·JPA·Web 에도
의존하지 않는다 — 그래야 컨텍스트 없이 단위 테스트할 수 있다 (테스트 컨벤션이
"domain 레이어는 단위 테스트로 충분하다"고 정했다).

게이트가 검사하는 것 — `DOMAIN_REFERENCES_NOTHING` · `DOMAIN_IS_FRAMEWORK_FREE`.
`REPOSITORY_INTERFACE_LIVES_IN_INFRA` 는 반대로 **저장소 인터페이스가 여기 있으면**
잡는다.

- 값 객체는 **불변**으로 만든다
- **상태 전이 규칙은 도메인 객체 안에 둔다.** service 에서 상태 필드를 직접 바꾸지 않는다
- 단순 데이터 컨테이너가 아니라 의미 있는 행위를 제공한다
- **저장소 인터페이스를 여기 두지 않는다.** infra 에 두고 Spring Data 를 상속한다
  (아키텍처 컨벤션 「저장소 인터페이스를 domain에 두지 않는 이유」)
- `@Entity` 는 여기 산다. 도메인 모델과 Entity 를 나누지 않으므로 변환 코드가 없다.
  `jakarta.persistence` 는 Spring 이 아니라서 프레임워크 자유 규칙에 걸리지 않는다
- 한 객체에 담기 어려운 규칙은 **도메인 서비스**로 뺀다. 역할 중심으로 이름을
  짓고 (`StoreAccessValidator` · `PriceCalculator` · `PointUsePolicy`) 상태를 갖지 않는다

식별자는 **유비쿼터스 언어를 그대로 쓴다** — `docs/wiki/02-설계-아키텍처/도메인-모델링.md`
1장. 여기서 지어낸 이름이 API 필드명으로 굳고, 응답 필드명은 클라이언트 계약이다.

불변식(`I-01`~`I-15`)은 같은 문서 5장에 **검증 위치와 이중 방어**까지 적혀 있다.
이중 방어가 DB 제약이면 검증은 통합 테스트다 — 단위 테스트로는 안 잡힌다.

상세: `docs/wiki/04-협업-규칙/아키텍처-컨벤션.md`
