package com.duckmoim.auth.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

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
@AutoConfigureMockMvc
@DisplayName("개발용 토큰 발급은 운영에 존재하지 않는다")
class DevTokenNotInProdTest {

  @Autowired private ApplicationContext applicationContext;
  @Autowired private MockMvc mockMvc;

  @Test
  @DisplayName("개발용 토큰 발급은 운영 프로파일에 등록되지 않는다.")
  void devTokenControllerIsNotRegistered() {
    assertThat(applicationContext.getBeanNamesForType(DevTokenController.class)).isEmpty();
  }

  /**
   * 방어의 둘째 겹이다. 위 검사는 「빈이 없다」까지만 보는데, 그것은 컨트롤러에 붙은 {@code @Profile("local")} 한 줄에 달려 있다. 경로가
   * 시큐리티에서도 빠져 있어야 그 한 줄이 어긋나는 날에도 관리자 토큰 발급기가 인증 없이 열리지 않는다 — 그 문 안에 비밀 댓글 본문이 있다 (CM-17).
   *
   * <p>404 가 아니라 <b>401</b> 이다. 경로가 {@code permitAll} 이던 동안에는 인가를 통과해 핸들러 탐색까지 가서 404 였다 — 그 404 가
   * 곧 「인가에 걸리지 않았다」는 뜻이었다. 인가에서 막히면 없는 경로의 존재 여부를 비인증 요청에 알려주지 않는 쪽으로 실패한다 (API-설계 「5. 결정 사항」
   * D-13).
   */
  @Test
  @DisplayName("개발용 토큰 발급 경로는 운영 프로파일에서 인가를 통과하지 못한다.")
  void devTokenPathIsNotPermitted() throws Exception {
    mockMvc
        .perform(post("/api/v1/dev/token").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isUnauthorized());
  }
}
