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
      "duckmoim.cors.allowed-origins=https://duckmoim.com",
      // prod 는 카카오 열쇠에 기본값을 두지 않는다. 없으면 기동이 실패하고,
      // 그 실패가 이 테스트를 「문서가 닫혔는지」와 무관한 이유로 빨갛게 만든다.
      "duckmoim.kakao.client-id=prod-profile-test-dummy-rest-api-key",
      // 적재 키도 같다 (D-11). 기본값을 두면 개발용 키로 운영이 떠서, 누구나 가짜
      // 행사를 밀어 넣을 수 있다.
      "duckmoim.ingest.key=prod-profile-test-dummy-ingest-key",
      // prod 는 S3 버킷에도 기본값을 두지 않는다. 버킷 이름이 있으면 S3 구현이 뜨는데
      // 자격증명이 없어도 빈 생성 자체는 되므로 컨텍스트가 올라간다 — 실제 호출만 실패한다.
      "duckmoim.s3.bucket=prod-profile-test-dummy-bucket",
      "duckmoim.s3.public-base-url=https://cdn.duckmoim.com"
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
