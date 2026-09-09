package com.duckmoim.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

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
   * <p><b>infra 는 service 가 접근한다.</b> 아키텍처 컨벤션 규칙 3 이 "service는 저장소와 외부 API Client를 infra에서 직접
   * 주입받는다. 그 앞에 별도 인터페이스를 두지 않는다" 로 정했다. 이 규칙은 그 앞 판본(포트-어댑터)을 보고 짜여 있었고, 그대로 두면 DB 를 읽는 service 를
   * 아예 쓸 수 없다 — infra 에 두면 여기 걸리고 domain 에 두면 {@link #DOMAIN_IS_FRAMEWORK_FREE} 에 걸린다.
   *
   * <p>withOptionalLayers 가 필요한 이유 — 아직 네 레이어를 다 갖춘 도메인이 없어 빈 레이어가 생기고, 이것 없이는 "Layer
   * 'presentation' is empty" 로 빌드가 깨진다. <b>대신 규칙이 아무것도 검사하지 않으면서 초록불이 된다.</b> 그래서 RulesAreAliveTest
   * 가 선택이 아니라 필수다.
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
          .mayOnlyBeAccessedByLayers("service")
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
   * 저장소 인터페이스는 infra 에 둔다 (아키텍처 컨벤션 「infra · 저장소」).
   *
   * <p><b>방향이 뒤집혔다.</b> 예전에는 domain 이 선언하고 infra 가 구현하는 포트-어댑터였다. 아키텍처 컨벤션 「패키지 구조」가 그 방식을 기각했다 —
   * 얻는 것은 DB 기술 교체의 격리 하나인데 로컬·운영 모두 MySQL 이라 바꿀 계획이 없고, 대신 도메인 모델과 {@code @Entity} 를 따로 두고 변환 코드를
   * 유지하는 비용을 계속 치른다. <b>변환 누락은 컴파일도 테스트도 통과한 뒤 화면에서야 드러난다.</b>
   *
   * <p>저장소는 Spring Data 를 상속하므로 domain 에 두면 {@link #DOMAIN_IS_FRAMEWORK_FREE} 와 정면으로 부딪힌다 — 두 규칙을
   * 동시에 만족시키는 배치가 없어진다.
   *
   * <p>그 대가로 service 가 Spring Data 타입을 알게 되는데, 그것은 "Spring Data 타입을 service public 시그니처에 노출하지 않는다"
   * 는 별도 규칙으로 관리한다. 그쪽은 기계가 판정하기 어려워 리뷰 체크리스트에 남아 있다.
   */
  static final ArchRule REPOSITORY_INTERFACE_LIVES_IN_INFRA =
      classes()
          .that()
          .haveSimpleNameEndingWith("Repository")
          .and()
          .areInterfaces()
          .should()
          .resideInAPackage("..infra..")
          .because("저장소 인터페이스는 infra 에 두고 Spring Data 를 상속한다 (아키텍처 컨벤션 · infra 저장소)")
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

  /**
   * {@code @Transactional} 은 service 에만 붙인다 (아키텍처 컨벤션 「트랜잭션」).
   *
   * <p>트랜잭션 경계가 흩어지면 어디서 열리고 닫히는지를 코드를 다 읽어야 알 수 있다. presentation 에 붙으면 뷰 렌더링까지 트랜잭션 안이고, infra 에
   * 붙으면 저장소 호출마다 경계가 생겨 service 의 유스케이스 하나가 원자적이지 않게 된다.
   *
   * <p><b>STAR-22 가 미룬 규칙이다.</b> 그때는 {@code spring-tx} 가 클래스패스에 없어 위반 픽스처가 컴파일되지 않았고, 규칙이 사는지 확인할
   * 방법이 없어서 넣지 않았다. STAR-29 로 {@code starter-data-jpa} 가 들어오면서 풀렸다.
   *
   * <p>클래스와 메서드 양쪽을 본다. ArchUnit 의 {@code beAnnotatedWith} 는 둘 중 하나만 보므로 조건을 직접 쓴다.
   */
  static final ArchRule TRANSACTIONAL_LIVES_IN_SERVICE =
      noClasses()
          .that()
          .resideOutsideOfPackage("..service..")
          .should(haveTransactionalAnywhere())
          .because("트랜잭션 경계는 service 의 public 메서드에 둔다 (아키텍처 컨벤션 · 트랜잭션)")
          .allowEmptyShould(true);

  /**
   * service 의 public 시그니처에 Spring Data 타입을 노출하지 않는다 (아키텍처 컨벤션 「service · 금지」).
   *
   * <p>{@code Page} · {@code Slice} · {@code Pageable} · {@code Example} 이 유스케이스 시그니처에 나오면 저장 기술이
   * 업무 흐름의 어휘가 된다. 페이징 조건은 커맨드로 받고 결과는 결과 객체로 반환한다 — {@code EventQuery} 와 {@code EventSlice} 가 그
   * 모양이다.
   *
   * <p><b>메서드 안에서 쓰는 것은 허용이다.</b> 저장소가 Spring Data 를 상속하므로 service 가 그 타입을 아예 못 만지면 조회를 할 수 없다. 그래서
   * 의존성 전체가 아니라 <b>시그니처만</b> 본다 — 반환 타입과 파라미터 타입.
   *
   * <p>한계 하나 — 제네릭 인자는 못 본다. {@code List<Page<Event>>} 의 raw 반환 타입은 {@code List} 라서 통과한다. 바이트코드에서
   * raw 타입만 보는 ArchUnit 의 성질이고, 흔한 모양이 아니라 여기까지만 막는다.
   */
  static final ArchRule SERVICE_DOES_NOT_EXPOSE_SPRING_DATA_TYPES =
      noMethods()
          .that()
          .arePublic()
          .and()
          .areDeclaredInClassesThat()
          .resideInAPackage("..service..")
          .should(haveSpringDataTypeInSignature())
          .because("페이징 조건은 커맨드로 받고 결과는 결과 객체로 반환한다 (아키텍처 컨벤션 · service 금지)")
          .allowEmptyShould(true);

  private static final String SPRING_DATA_PACKAGE = "org.springframework.data.domain";

  private static ArchCondition<JavaClass> haveTransactionalAnywhere() {
    return new ArchCondition<>("@Transactional 을 갖는다") {
      @Override
      public void check(JavaClass item, ConditionEvents events) {
        if (item.isAnnotatedWith(Transactional.class)) {
          events.add(
              SimpleConditionEvent.satisfied(
                  item, item.getName() + " 클래스에 @Transactional 이 붙어 있다"));
        }
        for (JavaMethod method : item.getMethods()) {
          if (method.isAnnotatedWith(Transactional.class)) {
            events.add(
                SimpleConditionEvent.satisfied(
                    item, method.getFullName() + " 에 @Transactional 이 붙어 있다"));
          }
        }
      }
    };
  }

  private static ArchCondition<JavaMethod> haveSpringDataTypeInSignature() {
    return new ArchCondition<>("시그니처에 Spring Data 타입을 갖는다") {
      @Override
      public void check(JavaMethod item, ConditionEvents events) {
        if (isSpringData(item.getRawReturnType())) {
          events.add(
              SimpleConditionEvent.satisfied(
                  item,
                  item.getFullName() + " 의 반환 타입이 " + item.getRawReturnType().getName() + " 다"));
        }
        for (JavaClass parameter : item.getRawParameterTypes()) {
          if (isSpringData(parameter)) {
            events.add(
                SimpleConditionEvent.satisfied(
                    item, item.getFullName() + " 의 파라미터에 " + parameter.getName() + " 가 있다"));
          }
        }
      }

      private boolean isSpringData(JavaClass type) {
        return type.getPackageName().startsWith(SPRING_DATA_PACKAGE);
      }
    };
  }

  private ArchitectureRules() {}
}
