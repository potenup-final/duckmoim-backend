package com.duckmoim.auth.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import com.duckmoim.auth.domain.AuthUser;
import com.duckmoim.auth.domain.TokenProvider;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 위키 {@code API-설계.md} 「엔드포인트 목록」의 권한 열을 그대로 옮긴 표다. <b>표 한 행 = 여기 한 줄</b> 이므로 눈으로 대조할 수 있고, {@code
 * SecurityConfig} 를 와일드카드로 아무리 묶어도 기대값은 이 표가 정한다.
 *
 * <p>엔드포인트를 추가하는 사람은 이 표에도 한 줄을 더한다. 안 더하면 등급이 검증되지 않고, 잘못 더하면 빨간불이 난다.
 *
 * <p><b>컨트롤러가 없어도 정확하다.</b> 인가는 핸들러 탐색보다 먼저 돌아서, 통과하면 404 가 나고 막히면 401·403 이 난다. 그래서 「막혔는가」는 컨트롤러
 * 유무와 무관하게 판정된다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("엔드포인트별 권한 등급")
class EndpointGradeTest {

  private enum Grade {
    PUBLIC,
    AUTH,
    SIGNUP,
    ADMIN
  }

  private record Endpoint(HttpMethod method, String path, Grade grade) {
    @Override
    public String toString() {
      return method + " " + path;
    }
  }

  /** HOST 는 관문이 판정하지 않는다. 방장 여부는 service 가 보므로 관문에서는 SIGNUP 으로 받는다. */
  private static final List<Endpoint> MATRIX =
      List.of(
          // 2-1 인증
          new Endpoint(HttpMethod.POST, "/api/v1/auth/kakao", Grade.PUBLIC),
          new Endpoint(HttpMethod.POST, "/api/v1/auth/token", Grade.PUBLIC),
          new Endpoint(HttpMethod.DELETE, "/api/v1/auth/token", Grade.AUTH),
          // 2-2 회원
          new Endpoint(HttpMethod.GET, "/api/v1/users/me", Grade.AUTH),
          new Endpoint(HttpMethod.GET, "/api/v1/users/nickname-availability", Grade.AUTH),
          new Endpoint(HttpMethod.PUT, "/api/v1/users/me/signup-info", Grade.AUTH),
          new Endpoint(HttpMethod.PATCH, "/api/v1/users/me/profile", Grade.SIGNUP),
          new Endpoint(HttpMethod.POST, "/api/v1/users/me/profile-image", Grade.SIGNUP),
          new Endpoint(HttpMethod.GET, "/api/v1/users/me/posts", Grade.SIGNUP),
          new Endpoint(HttpMethod.GET, "/api/v1/users/me/comments", Grade.SIGNUP),
          new Endpoint(HttpMethod.DELETE, "/api/v1/users/me", Grade.SIGNUP),
          new Endpoint(HttpMethod.GET, "/api/v1/users/9", Grade.PUBLIC),
          new Endpoint(HttpMethod.GET, "/api/v1/users/9/posts", Grade.PUBLIC),
          // 2-3 행사
          new Endpoint(HttpMethod.GET, "/api/v1/events", Grade.PUBLIC),
          new Endpoint(HttpMethod.GET, "/api/v1/events/pg_8709", Grade.PUBLIC),
          // 2-4 모집글
          new Endpoint(HttpMethod.GET, "/api/v1/posts", Grade.PUBLIC),
          new Endpoint(HttpMethod.POST, "/api/v1/posts", Grade.SIGNUP),
          new Endpoint(HttpMethod.GET, "/api/v1/posts/1", Grade.PUBLIC),
          new Endpoint(HttpMethod.PATCH, "/api/v1/posts/1", Grade.SIGNUP),
          new Endpoint(HttpMethod.POST, "/api/v1/posts/1/close", Grade.SIGNUP),
          // 2-5 댓글
          new Endpoint(HttpMethod.GET, "/api/v1/posts/1/comments", Grade.PUBLIC),
          new Endpoint(HttpMethod.POST, "/api/v1/posts/1/comments", Grade.SIGNUP),
          new Endpoint(HttpMethod.PATCH, "/api/v1/comments/1", Grade.SIGNUP),
          new Endpoint(HttpMethod.DELETE, "/api/v1/comments/1", Grade.SIGNUP),
          // 2-6 신고
          new Endpoint(HttpMethod.POST, "/api/v1/reports", Grade.SIGNUP),
          // 2-7 백오피스
          new Endpoint(HttpMethod.GET, "/api/v1/admin/reports", Grade.ADMIN),
          new Endpoint(HttpMethod.PATCH, "/api/v1/admin/reports/1", Grade.ADMIN),
          new Endpoint(HttpMethod.GET, "/api/v1/admin/comments/1", Grade.ADMIN),
          new Endpoint(HttpMethod.POST, "/api/v1/admin/comments/1/blind", Grade.ADMIN),
          new Endpoint(HttpMethod.POST, "/api/v1/admin/users/9/sanctions", Grade.ADMIN),
          new Endpoint(HttpMethod.DELETE, "/api/v1/admin/users/9/sanctions/1", Grade.ADMIN),
          new Endpoint(HttpMethod.GET, "/api/v1/admin/audit-logs", Grade.ADMIN));

  private static final AuthUser SIGNUP_INCOMPLETE = new AuthUser(1L, false, false);
  private static final AuthUser SIGNUP_COMPLETED = new AuthUser(2L, true, false);
  private static final AuthUser ADMINISTRATOR = new AuthUser(3L, true, true);

  @Autowired private MockMvc mockMvc;
  @Autowired private TokenProvider tokenProvider;

  private static Stream<Endpoint> publicEndpoints() {
    return endpointsOf(Grade.PUBLIC);
  }

  private static Stream<Endpoint> authEndpoints() {
    return endpointsOf(Grade.AUTH);
  }

  private static Stream<Endpoint> signupEndpoints() {
    return endpointsOf(Grade.SIGNUP);
  }

  private static Stream<Endpoint> adminEndpoints() {
    return endpointsOf(Grade.ADMIN);
  }

  private static Stream<Endpoint> endpointsOf(Grade grade) {
    return MATRIX.stream().filter(endpoint -> endpoint.grade() == grade);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("publicEndpoints")
  @DisplayName("PUBLIC 경로는 토큰이 없어도 인가를 통과한다.")
  void publicPassesWithoutToken(Endpoint endpoint) throws Exception {
    assertThat(statusOf(endpoint, null)).isNotIn(401, 403);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authEndpoints")
  @DisplayName("AUTH 경로는 토큰이 없으면 401 이다.")
  void authRejectsAnonymous(Endpoint endpoint) throws Exception {
    assertThat(statusOf(endpoint, null)).isEqualTo(401);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authEndpoints")
  @DisplayName("AUTH 경로는 가입 미완료 계정도 인가를 통과한다.")
  void authPassesSignupIncomplete(Endpoint endpoint) throws Exception {
    assertThat(statusOf(endpoint, SIGNUP_INCOMPLETE)).isNotIn(401, 403);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("signupEndpoints")
  @DisplayName("SIGNUP 경로는 토큰이 없으면 401 이다.")
  void signupRejectsAnonymous(Endpoint endpoint) throws Exception {
    assertThat(statusOf(endpoint, null)).isEqualTo(401);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("signupEndpoints")
  @DisplayName("SIGNUP 경로는 가입 미완료 계정을 403 으로 막는다.")
  void signupRejectsSignupIncomplete(Endpoint endpoint) throws Exception {
    assertThat(statusOf(endpoint, SIGNUP_INCOMPLETE)).isEqualTo(403);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("signupEndpoints")
  @DisplayName("SIGNUP 경로는 가입을 마친 계정이 인가를 통과한다.")
  void signupPassesSignupCompleted(Endpoint endpoint) throws Exception {
    assertThat(statusOf(endpoint, SIGNUP_COMPLETED)).isNotIn(401, 403);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("adminEndpoints")
  @DisplayName("ADMIN 경로는 토큰이 없으면 401 이다.")
  void adminRejectsAnonymous(Endpoint endpoint) throws Exception {
    assertThat(statusOf(endpoint, null)).isEqualTo(401);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("adminEndpoints")
  @DisplayName("ADMIN 경로는 일반 계정을 403 으로 막는다.")
  void adminRejectsNormalAccount(Endpoint endpoint) throws Exception {
    assertThat(statusOf(endpoint, SIGNUP_COMPLETED)).isEqualTo(403);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("adminEndpoints")
  @DisplayName("ADMIN 경로는 관리자가 인가를 통과한다.")
  void adminPassesAdministrator(Endpoint endpoint) throws Exception {
    assertThat(statusOf(endpoint, ADMINISTRATOR)).isNotIn(401, 403);
  }

  /**
   * 표에 없는 경로가 열려 있지 않은지 본다.
   *
   * <p>위 매트릭스는 <b>선언한 경로</b>만 검사하므로, 여는 쪽 와일드카드를 {@code /api/v1/posts/**} 처럼 다시 넓혀도 초록불이 난다. 실측했다.
   * 그래서 「아직 없는 경로」를 직접 찔러 <b>기본이 닫힘</b>인 것을 못박는다.
   *
   * <p>여기 적힌 주소는 실제 엔드포인트가 아니라 <b>앞으로 생길 수 있는 자리</b>다. 그 자리에 컨트롤러가 붙는 날 등급을 정해 위 표에 올리게 만드는 것이 이
   * 테스트의 목적이다.
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "/api/v1/posts/1/participants",
        "/api/v1/posts/1/secret-notes",
        "/api/v1/events/1/attendees",
        "/api/v1/users/9/comments",
        "/api/v1/comments/1/reactions"
      })
  @DisplayName("표에 선언하지 않은 경로는 토큰이 없으면 401 이다.")
  void undeclaredPathStaysClosed(String path) throws Exception {
    assertThat(statusOf(new Endpoint(HttpMethod.GET, path, Grade.PUBLIC), null)).isEqualTo(401);
  }

  @ParameterizedTest
  @ValueSource(strings = {"/api/v1/auth/admin-token", "/api/v1/posts/1/participants"})
  @DisplayName("표에 선언하지 않은 쓰기 경로는 토큰이 없으면 401 이다.")
  void undeclaredWritePathStaysClosed(String path) throws Exception {
    assertThat(statusOf(new Endpoint(HttpMethod.POST, path, Grade.PUBLIC), null)).isEqualTo(401);
  }

  private int statusOf(Endpoint endpoint, AuthUser authUser) throws Exception {
    HttpHeaders headers = new HttpHeaders();

    if (authUser != null) {
      headers.setBearerAuth(tokenProvider.issueAccessToken(authUser));
    }
    return mockMvc
        .perform(request(endpoint.method(), endpoint.path()).headers(headers))
        .andReturn()
        .getResponse()
        .getStatus();
  }
}
