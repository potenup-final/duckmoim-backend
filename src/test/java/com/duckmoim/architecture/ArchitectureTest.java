package com.duckmoim.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 프로덕션 코드가 아키텍처 컨벤션을 지키는지 검사한다. */
@AnalyzeClasses(packages = "com.duckmoim", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  @ArchTest static final ArchRule 레이어_의존성_방향 = ArchitectureRules.LAYER_DEPENDENCY;

  @ArchTest
  static final ArchRule domain_은_다른_레이어를_참조하지_않는다 = ArchitectureRules.DOMAIN_REFERENCES_NOTHING;

  @ArchTest
  static final ArchRule domain_은_Spring_에_의존하지_않는다 = ArchitectureRules.DOMAIN_IS_FRAMEWORK_FREE;

  @ArchTest
  static final ArchRule 저장소_인터페이스는_domain_에_있다 =
      ArchitectureRules.REPOSITORY_INTERFACE_LIVES_IN_DOMAIN;

  /**
   * @DisplayName 규칙만 테스트 코드를 대상으로 하므로 @ArchTest 로 쓸 수 없다. 위 @AnalyzeClasses 가 DoNotIncludeTests 로
   * 테스트 클래스를 아예 제외하기 때문이다.
   *
   * <p>위반 픽스처는 일부러 규칙을 어기므로 이 검사에서 빼야 한다. 규칙 자체에 예외를 넣지 않고 <b>클래스를 모으는 단계에서</b> 거른다 — 그래야
   * RulesAreAliveTest 가 같은 규칙 객체를 그대로 쓸 수 있다.
   */
  @DisplayName("모든 @Test 메서드에 @DisplayName 이 붙어 있다.")
  @Test
  void allTestsHaveDisplayName() {
    JavaClasses tests =
        new ClassFileImporter()
            .withImportOption(location -> !location.contains("/architecture/fixture/"))
            .importPackages("com.duckmoim");

    ArchitectureRules.TEST_HAS_DISPLAY_NAME.check(tests);
  }
}
