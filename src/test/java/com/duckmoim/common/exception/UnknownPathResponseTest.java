package com.duckmoim.common.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.duckmoim.auth.domain.AuthUser;
import com.duckmoim.auth.domain.TokenProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * 없는 경로 404 만 실제 컨텍스트로 확인한다.
 *
 * <p>슬라이스로는 재현되지 않는 케이스다. 정적 리소스 매핑이 걸려 있는지에 따라 {@code NoHandlerFoundException} 과 {@code
 * NoResourceFoundException} 중 무엇이 뜨는지가 갈리고, 그 둘은 다른 경로로 어드바이스에 도달한다. 슬라이스만 보고 초록불을 받으면 운영에서 다른 예외가
 * 뜬다.
 *
 * <p><b>STAR-41 이후 토큰을 붙여서 확인한다.</b> 인가가 디스패처보다 먼저 도므로, 등급을 선언하지 않은 경로는 인증된 요청만 핸들러 탐색까지 간다. 토큰 없이
 * 부르면 404 가 아니라 401 이고 그것도 아래에서 함께 못박는다 — 없는 경로의 존재 여부를 비인증 요청에 알려주지 않는 편이 낫다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UnknownPathResponseTest {

  @Autowired private TestRestTemplate restTemplate;
  @Autowired private TokenProvider tokenProvider;

  @DisplayName("존재하지 않는 경로를 요청하면 404 와 공통 에러 봉투가 나간다.")
  @Test
  void unknownPath() {
    // when
    ResponseEntity<ErrorResponse> response = getAuthenticated("/api/v1/does-not-exist");

    // then
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().code()).isEqualTo("ENDPOINT_NOT_FOUND");
  }

  @DisplayName("토큰 없이 존재하지 않는 경로를 요청하면 404 가 아니라 401 이다.")
  @Test
  void unknownPathWithoutToken() {
    // when
    ResponseEntity<ErrorResponse> response =
        restTemplate.getForEntity("/api/v1/does-not-exist", ErrorResponse.class);

    // then
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  /**
   * {@link GlobalExceptionHandlerTest} 의 테스트 전용 컨트롤러가 이 컨텍스트에 없어야 한다. 올라와 있으면 {@code
   * /test/business} 가 409 로 응답한다 — 그러면 위 검증이 통과해도 실제 컨텍스트에는 가짜 엔드포인트가 있는 상태다.
   */
  @DisplayName("테스트 전용 컨트롤러는 실제 컨텍스트에 등록되지 않는다.")
  @Test
  void testOnlyControllerIsNotRegistered() {
    // when
    ResponseEntity<ErrorResponse> response = getAuthenticated("/test/business");

    // then
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  private ResponseEntity<ErrorResponse> getAuthenticated(String path) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(tokenProvider.issueAccessToken(new AuthUser(1L, true, false)));

    return restTemplate.exchange(
        path, HttpMethod.GET, new HttpEntity<>(headers), ErrorResponse.class);
  }
}
