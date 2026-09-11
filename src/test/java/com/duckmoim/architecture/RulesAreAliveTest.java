package com.duckmoim.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 아키텍처 규칙이 <b>실제로 위반을 잡는지</b> 검사한다.
 *
 * <p>왜 필요한가 — 도메인 코드가 아직 0줄이라 레이어 규칙은 빈 레이어를 통과한다. 그 상태에서 ArchitectureTest 는 초록불이지만 아무것도 검증하지 않는다.
 * 규칙이 잘못 적혔는지, 패키지 표현식이 어긋났는지, 애초에 동작하지 않는지 알 방법이 없다.
 *
 * <p>그래서 위반 픽스처를 두고 규칙이 그것을 잡는지 확인한다. CI/CD 계획 Phase 1 의 완료 조건 ②(<i>일부러 테스트를 깨뜨린 PR 에서 ❌ 가 뜨는지
 * 확인</i>)를 사람 손에서 떼어내 테스트로 만든 것이다.
 *
 * <p><b>규칙을 추가할 때는 여기에도 한 줄을 추가한다.</b> 안 하면 그 규칙은 있는 척만 한다.
 */
class RulesAreAliveTest {

  private static final String FIXTURE_PACKAGE = "com.duckmoim.architecture.fixture";

  /** {@code installGitHooks} 가 {@code core.hooksPath} 로 심는 자리 (STAR-130). */
  private static final String HOOKS_DIR = ".githooks";

  private static EvaluationResult evaluateOnFixtures(ArchRule rule) {
    JavaClasses fixtures = new ClassFileImporter().importPackages(FIXTURE_PACKAGE);
    return rule.evaluate(fixtures);
  }

  private static void assertCatches(ArchRule rule, String expectedClassName) {
    EvaluationResult result = evaluateOnFixtures(rule);

    assertThat(result.hasViolation()).as("규칙이 위반 픽스처를 잡지 못했다. 규칙이 잘못 적혔거나 패키지 표현식이 어긋났다").isTrue();
    assertThat(result.getFailureReport().toString()).contains(expectedClassName);
  }

  @DisplayName("presentation 이 service 를 건너뛰고 infra 를 참조하면 레이어 규칙이 잡는다.")
  @Test
  void layerDependency() {
    assertCatches(ArchitectureRules.LAYER_DEPENDENCY, "ViolatingPaymentController");
  }

  /**
   * 규칙이 <b>과하게 잡지 않는지</b>도 본다. service → infra 는 아키텍처 컨벤션 규칙 3 이 허용한 방향이고, 이것이 위반으로 잡히면 DB 를 읽는
   * service 를 아예 쓸 수 없다.
   *
   * <p>여기에 이 테스트가 필요한 이유 — 규칙을 뒤집을 때 방향을 반대로 적어도 위반 픽스처는 여전히 잡히므로 위의 검사만으로는 초록불이 된다.
   */
  @DisplayName("service 가 infra 저장소를 주입받는 것은 레이어 규칙이 잡지 않는다.")
  @Test
  void layerDependency_serviceMayUseInfra() {
    EvaluationResult result = evaluateOnFixtures(ArchitectureRules.LAYER_DEPENDENCY);

    // 보고서 전체에서 이름을 찾으면 안 된다. PaymentService 는 ViolatingPayment(domain) 가
    // 참조하는 *대상* 으로도 등장하므로, 그건 domain 쪽 위반이지 service 쪽 위반이 아니다.
    assertThat(result.getFailureReport().getDetails())
        .as("service → infra 는 허용된 방향이다 (아키텍처 컨벤션 규칙 3)")
        .noneMatch(
            detail ->
                detail.contains("fixture.service.PaymentService")
                    && detail.contains("PaymentJpaRepository"));
  }

  @DisplayName("domain 이 상위 레이어를 참조하면 잡는다.")
  @Test
  void domainReferencesNothing() {
    assertCatches(ArchitectureRules.DOMAIN_REFERENCES_NOTHING, "ViolatingPayment");
  }

  @DisplayName("domain 이 Spring 애너테이션에 묶이면 잡는다.")
  @Test
  void domainIsFrameworkFree() {
    assertCatches(ArchitectureRules.DOMAIN_IS_FRAMEWORK_FREE, "ViolatingPayment");
  }

  @DisplayName("저장소 인터페이스가 infra 밖에 있으면 잡는다.")
  @Test
  void repositoryInterfaceLivesInInfra() {
    assertCatches(ArchitectureRules.REPOSITORY_INTERFACE_LIVES_IN_INFRA, "MisplacedRepository");
  }

  @DisplayName("@Test 에 @DisplayName 이 없으면 잡는다.")
  @Test
  void testHasDisplayName() {
    assertCatches(ArchitectureRules.TEST_HAS_DISPLAY_NAME, "MissingDisplayNameFixture");
  }

  @DisplayName("service 밖에 @Transactional 을 붙이면 트랜잭션 규칙이 잡는다.")
  @Test
  void transactionalLivesInService() {
    assertCatches(ArchitectureRules.TRANSACTIONAL_LIVES_IN_SERVICE, "TransactionalRepository");
  }

  @DisplayName("service 의 public 시그니처에 Spring Data 타입이 나오면 노출 규칙이 잡는다.")
  @Test
  void serviceDoesNotExposeSpringDataTypes() {
    assertCatches(
        ArchitectureRules.SERVICE_DOES_NOT_EXPOSE_SPRING_DATA_TYPES, "LeakingPageService");
  }

  /**
   * 규칙이 <b>과하게 잡지 않는지</b>도 본다. 메서드 안에서 Spring Data 타입을 쓰는 것은 허용이다 — 저장소가 Spring Data 를 상속하므로 이것까지
   * 막으면 DB 를 읽는 service 를 쓸 수 없다.
   *
   * <p>의존성 전체를 검사하는 규칙으로 잘못 적으면 위반 픽스처는 여전히 잡히므로 위의 검사만으로는 초록불이 된다.
   */
  @DisplayName("service 가 메서드 안에서 Spring Data 타입을 쓰는 것은 노출 규칙이 잡지 않는다.")
  @Test
  void serviceMayUseSpringDataInternally() {
    EvaluationResult result =
        evaluateOnFixtures(ArchitectureRules.SERVICE_DOES_NOT_EXPOSE_SPRING_DATA_TYPES);

    assertThat(result.getFailureReport().getDetails())
        .as("메서드 안에서 쓰는 것은 허용이다 (아키텍처 컨벤션 · service 금지)")
        .noneMatch(detail -> detail.contains("countInternally"));
  }

  /**
   * 위키 핀 게이트의 생존 증명 (STAR-130).
   *
   * <p>ArchUnit 규칙이 아니라 git 인덱스의 상태를 보는 검사라, 픽스처도 클래스가 아니라 저장소다 ({@link StagedWikiPinRepository}).
   * 그 밖은 위와 같다 — 위반을 만들어 두고 게이트가 그것을 잡는지, 그리고 <b>과하게 잡지는 않는지</b> 본다.
   */
  @DisplayName("인덱스에 위키 핀이 실려 있으면 서브모듈 핀 검사가 잡는다.")
  @Test
  void wikiSubmodulePinIsNotStaged(@TempDir Path tempDir) {
    Path repo = StagedWikiPinRepository.create(tempDir);

    assertThat(SubmodulePinTest.stagedPinMismatch(repo))
        .as("검사가 위반 픽스처를 잡지 못했다. 인덱스와 HEAD 를 비교하는 자리가 어긋났다")
        .isPresent();
  }

  @DisplayName("핀을 인덱스에서 빼면 서브모듈 핀 검사가 잡지 않는다.")
  @Test
  void wikiSubmodulePinIsNotStaged_afterUnstaging(@TempDir Path tempDir) {
    Path repo = StagedWikiPinRepository.create(tempDir);
    StagedWikiPinRepository.unstagePin(repo);

    assertThat(SubmodulePinTest.stagedPinMismatch(repo))
        .as("핀이 어긋나지 않았는데 잡았다. 늘 잡는 검사는 아무것도 증명하지 않는다")
        .isEmpty();
  }

  /**
   * 훅을 직접 실행하지 않고 <b>git 이 부르게 한다.</b> 그래서 이 검사는 훅의 내용만이 아니라 git 이 그것을 훅으로 인정하는지까지 본다 — 실행 권한이 빠지면
   * git 이 조용히 건너뛰므로 커밋이 통과하고, 여기서 빨간불이 난다.
   */
  @DisplayName("인덱스에 위키 핀이 실린 채로 커밋하면 pre-commit 훅이 막는다.")
  @Test
  void preCommitRejectsStagedWikiPin(@TempDir Path tempDir) {
    Path repo = StagedWikiPinRepository.create(tempDir);

    StagedWikiPinRepository.Execution commit = commitWithProjectHooks(repo);

    assertThat(commit.exitCode()).as("훅이 커밋을 막지 못했다. 출력: %s", commit.output()).isNotZero();
    assertThat(commit.output()).contains("위키 서브모듈 핀");
  }

  @DisplayName("핀을 인덱스에서 빼면 pre-commit 훅이 커밋을 통과시킨다.")
  @Test
  void preCommitRejectsStagedWikiPin_afterUnstaging(@TempDir Path tempDir) {
    Path repo = StagedWikiPinRepository.create(tempDir);
    StagedWikiPinRepository.unstagePin(repo);

    StagedWikiPinRepository.Execution commit = commitWithProjectHooks(repo);

    assertThat(commit.exitCode())
        .as("핀이 어긋나지 않았는데 막았다. 늘 막는 훅은 다음에 --no-verify 로 꺼진다. 출력: %s", commit.output())
        .isZero();
  }

  /** 픽스처 저장소에서 커밋을 시도하되, 훅은 <b>이 저장소의 것</b>을 쓰게 한다. */
  private static StagedWikiPinRepository.Execution commitWithProjectHooks(Path repo) {
    Path hooks = Path.of(HOOKS_DIR).toAbsolutePath();

    assertThat(hooks.resolve("pre-commit"))
        .as("%s 에 pre-commit 훅이 있어야 한다 (STAR-130)", HOOKS_DIR)
        .isRegularFile();

    return StagedWikiPinRepository.git(
        repo, "-c", "core.hooksPath=" + hooks, "commit", "--quiet", "-m", "핀과 무관한 변경");
  }
}
