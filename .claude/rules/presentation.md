---
paths:
  - "src/main/java/**/presentation/**"
---

# presentation 레이어

HTTP 요청·응답, Controller, API DTO, 인증 사용자 해석.

- Request·Response DTO 를 여기 둔다. **Entity 를 Response 로 직접 반환하지 않는다**
- service 에는 Command·Query 객체로 변환해서 넘긴다
- 응답 조립을 service 에 맡기지 않는다
- 비즈니스 로직을 깊게 검증하는 Controller 테스트를 쓰지 않는다 — 성공·실패
  조합은 통합 테스트에서 다룬다. 여기서는 HTTP 계약·상태 코드·인증·인가만 본다

API 계약은 **이미 정해져 있다.** `docs/wiki/02-설계-아키텍처/API-설계.md` 2장에
엔드포인트 목록, 3장에 커서 구성, 4장에 에러 코드 20개가 근거 ID 까지 붙어 있다.
새로 만들기 전에 거기 있는지 본다 — **같은 의미의 에러 코드를 중복 생성하지 않는다.**

표기 규칙 몇 가지가 자주 틀린다 (`docs/wiki/04-협업-규칙/API-컨벤션.md`).

- JSON 필드명은 **camelCase**. 도메인 식별자와 일치시킨다
- boolean 에 `is` 접두어를 붙이지 않는다 — `secret` (O) / `isSecret` (X)
- null 가능 필드는 생략하지 않고 null 로 명시한다
- **단, 권한에 따라 서버가 감추는 필드는 null 이 아니라 키 자체를 제거한다.**
  비밀 댓글 본문이 여기 해당한다 (CM-05)
- 소프트 삭제된 리소스는 404 로 취급한다
- 존재 자체를 숨겨야 하면 403 이 아니라 404 를 쓴다

상세: `docs/wiki/04-협업-규칙/API-컨벤션.md`
