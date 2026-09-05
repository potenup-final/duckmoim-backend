package com.duckmoim.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 위키 04-협업-규칙/아키텍처-컨벤션.md 를 기계가 판정할 수 있는 형태로 옮긴 것.
 *
 * <p><b>빈 입력을 인정하는 설정이 붙어 있다.</b> 도메인 코드가 아직 0줄이라 규칙이 검사할 클래스를 하나도 못 만난다. ArchUnit 은 그 상황을 두 방식으로
 * 거부한다 — layeredArchitecture 는 "Layer 'domain' is empty", noClasses/classes 는 "failed to check any
 * classes". 둘 다 <i>지금은 검사할 게 없다</i>를 명시적으로 인정하게 만드는 좋은 설계이고, 그 인정의 대가가 {@link RulesAreAliveTest} 다.
 *
 * <p>규칙 정의가 여기 한 곳에만 있는 것이 중요하다. {@link ArchitectureTest} 는 이 규칙으로 프로덕션 코드를 검사하고, {@link
 * RulesAreAliveTest} 는 <b>같은 규칙 객체</b>로 위반 픽스처를 검사한다. 규칙을 복제하면 둘이 갈라져서 생존 증명이 증명하지 않게 된다.
 */
final class ArchitectureRules {

  /**
   * 의존성은 아래 방향으로만 흐른다 (아키텍처 컨벤션 "의존성 방향").
   *
   * <p>withOptionalLayers 가 필요한 이유 — 도메인 코드가 아직 0줄이라 네 레이어가 모두 비어 있고, 이것 없이는 "Layer 'domain' is
   * empty" 로 빌드가 깨진다. <b>대신 규칙이 아무것도 검사하지 않으면서 초록불이 된다.</b> 그래서 RulesAreAliveTest 가 선택이 아니라 필수다.
   */
  static final ArchRule LAYER_DEPENDENCY =
      layeredArchitecture()
          .consideringOnlyDependenciesInLayers()
          .layer("presentation")
          .definedBy("..presentation..")
          .layer("service")
          .definedBy("..service..")
          .layer("domain")
          .definedBy("..domain..")
          .layer("infra")
          .definedBy("..infra..")
          .whereLayer("presentation")
          .mayNotBeAccessedByAnyLayer()
          .whereLayer("service")
          .mayOnlyBeAccessedByLayers("presentation")
          .whereLayer("infra")
          .mayNotBeAccessedByAnyLayer()
          .withOptionalLayers(true);

  /** domain 은 의존 그래프의 종착점이다. 어떤 레이어도 참조하지 않는다 (아키텍처 컨벤션 규칙 3). */
  static final ArchRule DOMAIN_REFERENCES_NOTHING =
      noClasses()
          .that()
          .resideInAPackage("..domain..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("..presentation..", "..service..", "..infra..")
          .because("domain 은 의존 그래프의 종착점이다 (아키텍처 컨벤션 · 의존성 방향)")
          .allowEmptyShould(true);

  /**
   * domain 은 프레임워크에 묶이지 않는다 (아키텍처 컨벤션 "도메인 모델").
   *
   * <p>domain 이 Spring 을 참조하기 시작하면 단위 테스트에 컨텍스트가 필요해지고, 테스트 컨벤션이 "domain 레이어는 단위 테스트로 충분하다"고 정한 것이
   * 무너진다.
   */
  static final ArchRule DOMAIN_IS_FRAMEWORK_FREE =
      noClasses()
          .that()
          .resideInAPackage("..domain..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("org.springframework..")
          .because("domain 을 Spring 없이 단위 테스트할 수 있어야 한다 (테스트 컨벤션 · 테스트 계층)")
          .allowEmptyShould(true);

  /**
   * 저장소 인터페이스는 infra 가 선언한다 (아키텍처 컨벤션 "infra · 저장소").
   *
   * <p>저장소는 Spring Data 를 상속한다. domain 에 두면 domain 이 프레임워크에 묶여 {@link #DOMAIN_IS_FRAMEWORK_FREE} 와
   * 정면으로 부딪힌다 — 두 규칙을 동시에 만족시키는 배치가 없어진다.
   */
  static final ArchRule REPOSITORY_INTERFACE_LIVES_IN_INFRA =
      classes()
          .that()
          .haveSimpleNameEndingWith("Repository")
          .and()
          .areInterfaces()
          .should()
          .resideInAPackage("..infra..")
          .because("저장소 인터페이스는 infra 가 선언한다 (아키텍처 컨벤션 · infra 저장소)")
          .allowEmptyShould(true);

  /**
   * 모든 @Test 에 @DisplayName 이 있다 (테스트 컨벤션 "네이밍 규칙").
   *
   * <p>"@DisplayName 만 읽고 무엇을 검증하는지 알 수 있어야 한다"가 근거다. 이 규칙만 테스트 코드를 대상으로 하므로 프로덕션 검사와 다른 방식으로 클래스를
   * 모아야 한다 (ArchitectureTest 참고).
   */
  static final ArchRule TEST_HAS_DISPLAY_NAME =
      methods()
          .that()
          .areAnnotatedWith(Test.class)
          .should()
          .beAnnotatedWith(DisplayName.class)
          .because("@DisplayName 만 읽고 무엇을 검증하는지 알 수 있어야 한다 (테스트 컨벤션 · 네이밍 규칙)");

  private ArchitectureRules() {}
}
