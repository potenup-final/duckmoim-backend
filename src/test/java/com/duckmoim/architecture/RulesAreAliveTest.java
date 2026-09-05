package com.duckmoim.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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

  private static EvaluationResult evaluateOnFixtures(ArchRule rule) {
    JavaClasses fixtures = new ClassFileImporter().importPackages(FIXTURE_PACKAGE);
    return rule.evaluate(fixtures);
  }

  private static void assertCatches(ArchRule rule, String expectedClassName) {
    EvaluationResult result = evaluateOnFixtures(rule);

    assertThat(result.hasViolation()).as("규칙이 위반 픽스처를 잡지 못했다. 규칙이 잘못 적혔거나 패키지 표현식이 어긋났다").isTrue();
    assertThat(result.getFailureReport().toString()).contains(expectedClassName);
  }

  @DisplayName("service 가 infra 를 직접 참조하면 레이어 규칙이 잡는다.")
  @Test
  void layerDependency() {
    assertCatches(ArchitectureRules.LAYER_DEPENDENCY, "ViolatingPaymentService");
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
}
