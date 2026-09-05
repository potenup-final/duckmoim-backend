package com.duckmoim.auth.presentation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(
    properties = {
      "duckmoim.jwt.secret=prod-profile-test-dummy-secret-not-a-real-key",
      "duckmoim.cors.allowed-origins=https://duckmoim.com"
    })
@ActiveProfiles("prod")
@DisplayName("개발용 토큰 발급은 운영에 존재하지 않는다")
class DevTokenNotInProdTest {

  @Autowired private ApplicationContext applicationContext;

  @Test
  @DisplayName("개발용 토큰 발급은 운영 프로파일에 등록되지 않는다.")
  void devTokenControllerIsNotRegistered() {
    assertThat(applicationContext.getBeanNamesForType(DevTokenController.class)).isEmpty();
  }
}
