규칙이 살아있는지 증명하기 위한 위반 픽스처다. 프로덕션 코드가 아니다.

각 클래스는 아키텍처 컨벤션을 **일부러** 어긴다. RulesAreAliveTest 가 이것들을
규칙에 통과시켜 "규칙이 실제로 잡는다"를 확인한다.

여기 있는 클래스는 프로덕션 검사에서 제외된다 — ArchitectureTest 의
DoNotIncludeTests 와, @DisplayName 검사의 경로 필터가 그 일을 한다.

이 디렉터리는 `build.gradle` 의 test 태스크에서 `exclude` 된다. 이름을 `*Test` 로
안 지으면 수집되지 않을 거라 봤지만 실제로는 실행됐다 — Gradle 은 클래스 파일을
훑어 JUnit Platform 에 넘기므로 이름 규칙이 걸리지 않는다. 컴파일은 그대로라
ArchUnit 은 계속 읽는다.
