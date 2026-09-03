# 덕모임 백엔드 작업 규칙

콘서트·팝업·생카 **동행 매칭** 서비스의 API 서버. Spring Boot 3.5 · Java 17 · Gradle 9.

설계와 컨벤션은 위키에 있고 `docs/wiki/` 에 서브모듈로 붙어 있다. 이 문서는
**위키에 없는 것**만 담는다 — 명령, 루프 순서, 겪은 함정.

하네스 전체 설명은 [docs/harness/backend.md](docs/harness/backend.md).

## 명령

```bash
./gradlew build          # 게이트 전부 (포맷·컨벤션·아키텍처·테스트). CI 와 같은 것
./gradlew check          # 위와 같음. 빌드 산출물은 안 만든다
./gradlew spotlessApply  # 포맷 위반을 자동으로 고친다
./gradlew bootRun        # localhost:8080. Swagger 는 /swagger-ui.html
```

`build` 는 웜 데몬에서 3초다. **자주 돌려라.** 한 번에 다 짜고 마지막에 돌리면
실패가 뭉쳐서 원인을 못 좁힌다.

## 루프

```
[1] 컨텍스트 확보  →  [2] 구현  →  [3] ./gradlew check  →  [4] PR
                          ↑______________|
                        실패하면 여기로. 사람을 부르지 않는다
```

### [1] 요구사항 ID 부터 찾는다

산출물은 **이 티켓이 통과해야 하는 테스트 목록**이다. 테스트 컨벤션이
*"요구사항 명세의 검증 기준이 곧 테스트 목록"* 이라고 못박았다. 창작이 아니라 수집이다.

| 무엇 | 어디에 |
|---|---|
| 요구사항의 검증 기준 | `docs/wiki/01-제품-요구사항/1차-MVP-기능-명세서.md` 2장 |
| 불변식과 검증 위치 | `docs/wiki/02-설계-아키텍처/도메인-모델링.md` 5장 |
| 유비쿼터스 언어 | 같은 문서 1장 |
| 에러 코드와 status | `docs/wiki/02-설계-아키텍처/API-설계.md` 4장 |
| 설계 결정 | 같은 문서 5장 · `ADR/` |

**ID 한 개만 보면 절반만 안다.** 요구사항(`AU-06`)과 불변식(`I-01`)이 서로를
인용하고, *어디서 강제하는지*는 불변식 표에만 적혀 있다. 접두어가 어느 문서
소속인지는 `docs/wiki/README.md` 의 **ID 체계** 표에 있다.

**설계 결정을 반드시 확인한다.** 여기가 가장 자주 틀린다 — 일반적인 모범 사례가
이 프로젝트에서 이미 기각된 경우가 있다. 예를 들어 D-1 은 *Refresh 토큰을
httpOnly 쿠키에 두지 않는다* 다 (프론트와 API 가 다른 도메인이라 서드파티
쿠키가 되고 Safari ITP 에 막힌다). D-3 은 *모집글 삭제를 두지 않는다* 다.

### [2] `domain → infra → service → presentation` 순서로 쓴다

의존성이 그 방향으로만 흐른다. 역순으로 쓰면 아직 없는 것을 참조하게 되고,
그때 급히 만든 인터페이스가 나중에 지워지지 않는다.

### [3] 게이트가 무엇을 보는가

`./gradlew check` 가 검사한다.

- **Spotless** (googleJavaFormat) — 포맷. 위반은 `spotlessApply` 로 고친다
- **Checkstyle** — 로그 prefix `[Class.method]`, 로그 문자열 더하기 금지,
  `FIXME`·`XXX`·`HACK` 금지, 메서드 네이밍
- **ArchUnit** — 레이어 의존성, domain 의 상위 레이어·Spring 참조,
  저장소 인터페이스 위치, `@Test` 에 `@DisplayName`

**게이트가 막으면 코드를 고친다. 규칙을 고치지 않는다.** 규칙 파일을 바꾸는
쪽이 쉽고 diff 도 작지만, 그러면 게이트가 게이트가 아니다. 규칙이 틀렸다고
판단되면 PR 리뷰 포인트에 근거를 적어 올린다.

**규칙을 추가하면 생존 증명도 같이 추가한다.** `RulesAreAliveTest` 가 위반
픽스처로 "규칙이 실제로 잡는지"를 검사한다. 도메인 코드가 아직 적어 규칙이
빈 입력을 통과하므로, 이것 없이는 초록불이 아무것도 증명하지 않는다.

## 겪은 함정

- **Checkstyle 메시지에 `{}` 를 쓰면 태스크가 터진다.** 메시지가
  `MessageFormat` 으로 처리돼 `{}` 를 인자 자리로 읽는다. `'{}'` 로 감싼다.
  무서운 것은 **규칙이 처음 걸리는 날까지 아무 이상이 없다**는 점이다
- **ArchUnit 은 빈 입력을 거부한다.** `layeredArchitecture` 는 빈 레이어로,
  `noClasses`/`classes` 는 매칭 0건으로 실패한다. `withOptionalLayers(true)` 와
  `allowEmptyShould(true)` 로 인정하되, 그 대가로 생존 증명이 필요해진다
- **위반 픽스처는 `test` 태스크에서 `exclude` 한다.** 이름을 `*Test` 로 안 지어도
  Gradle 이 클래스 파일을 훑어 수집한다

## 하면 안 되는 것

### 브랜치를 만들지 않는다

Jira 티켓을 **진행 중으로 옮기면** 워크플로가 만든다. 제목 접두사 한 단어가
브랜치·라벨·커밋 타입을 다 결정한다. `feat` `fix` `refactor` `test` `chore` `docs`.

### 커밋 제목에 `[KEY]` 를 붙이지 않는다

`.githooks/prepare-commit-msg` 가 브랜치명에서 뽑아 붙인다. `./gradlew build` 가
훅을 켜준다. 확인은 `git config core.hooksPath` → `.githooks` 가 나와야 한다.

커밋 본문은 **설명 3~4줄 + 나머지는 불렛포인트**로 쓴다. 무엇을 했는지는 diff 에
있으니 **왜 그렇게 했는지**를 남긴다.

### PR 본문 앞부분을 쓰지 않는다

Jira 정보는 `pr_body.py` 가 마커 사이에 자동으로 붙인다. 요구사항과 배경을
옮겨 적지 않는다.

### 공용 파일에 손대지 않는다

| 경로 | 상태 |
|---|---|
| `.githooks/**` · `.github/scripts/**` · `.github/workflows/jira-*.yml` · `.github/PULL_REQUEST_TEMPLATE.md` | 금지. 팀 공용 |
| `docs/wiki/**` | 금지. 서브모듈이라 여기서 고치면 사라진다 |
| `config/checkstyle/**` · `src/test/java/com/duckmoim/architecture/**` | 물어봄. 게이트 우회로 |
| `.github/workflows/ci-cd.yml` · `gradlew` · `gradle/wrapper/**` | 물어봄. 배포와 CI 가 걸린다 |

**위키를 고쳐야 하면** 별도 클론에서 하고 (`../duckmoim-wiki`), 여기서는
`git submodule update --remote docs/wiki` 로 포인터만 올린다.

## 데이터베이스

**아직 없다.** JPA·MySQL 은 의도적으로 빼뒀다 — 클래스패스에 있으면 접속 정보를
찾다가 기동 자체가 실패한다. 첫 엔티티 티켓에서 Testcontainers·Flyway 와 함께
넣는다. 테스트 컨벤션이 H2 를 금지했으므로 Testcontainers 가 사실상 결정돼 있다.
