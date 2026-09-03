package com.duckmoim.architecture.fixture.displayname;

import org.junit.jupiter.api.Test;

/**
 * 위반 — @Test 에 @DisplayName 이 없다.
 *
 * <p>클래스 이름이 `*Test` 가 아니라 JUnit Platform 이 수집하지 않는다. ArchUnit 은 여전히 클래스로 읽는다.
 */
class MissingDisplayNameFixture {

  @Test
  void pay() {}
}
