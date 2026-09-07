package com.duckmoim.auth.presentation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.duckmoim.auth.domain.AuthUser;
import com.duckmoim.auth.domain.TokenProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 이 PR 이 다른 컨텍스트에 파는 계약을 검증한다 — <b>컨트롤러가 {@code @AuthenticationPrincipal AuthUser} 로 요청자를 받는다.</b>
 *
 * <p>세 단계가 다 맞아야 성립한다. ① 필터가 {@link AuthUser} 를 만들어 SecurityContext 에 넣는다 ② Spring 이 그것을 꺼내 메서드
 * 파라미터에 주입한다 ③ 컨트롤러가 받는다. {@link AuthenticationFilterTest} 는 ①까지만 보고, 나머지 테스트에는 이 어노테이션을 쓰는 컨트롤러가
 * 없어서 <b>②가 한 번도 실행된 적이 없었다.</b>
 *
 * <p>어긋나면 조용히 실패한다 — Spring 은 타입이 안 맞으면 예외를 내지 않고 {@code null} 을 넣는다. 그러면 컨트롤러 입구가 아니라 service 안쪽에서
 * NPE 가 나고, 원인이 인증 설정이라는 것을 알아내는 데 시간이 걸린다.
 */
@WebMvcTest(AuthenticationPrincipalTest.PrincipalProbeController.class)
@Import(AuthenticationPrincipalTest.PrincipalProbeController.class)
@ImportSecurity
@DisplayName("컨트롤러가 받는 인증 주체")
class AuthenticationPrincipalTest {

  /** 실제 컨트롤러가 아직 이 어노테이션을 쓰지 않으므로 계약을 대신 실행해 보는 컨트롤러다. */
  @RestController
  static class PrincipalProbeController {

    record Probe(Long userId, boolean anonymous) {}

    @GetMapping("/api/v1/users/me")
    Probe authenticatedOnly(@AuthenticationPrincipal AuthUser authUser) {
      return probeOf(authUser);
    }

    @GetMapping("/api/v1/posts")
    Probe publicPath(@AuthenticationPrincipal AuthUser authUser) {
      return probeOf(authUser);
    }

    private Probe probeOf(AuthUser authUser) {
      if (authUser == null) {
        return new Probe(null, true);
      }
      return new Probe(authUser.userId(), false);
    }
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private TokenProvider tokenProvider;

  @Test
  @DisplayName("토큰을 보내면 컨트롤러에 토큰의 회원번호가 도착한다.")
  void principalCarriesUserId() throws Exception {
    mockMvc
        .perform(get("/api/v1/users/me").headers(bearer(new AuthUser(7L, true, false))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.userId").value(7))
        .andExpect(jsonPath("$.anonymous").value(false));
  }

  /** 공개 목록에 「내가 쓴 글」 표시를 붙이려면 로그인 여부를 컨트롤러가 알아야 한다. */
  @Test
  @DisplayName("공개 경로에서도 토큰을 보내면 회원번호가 도착한다.")
  void publicPathCarriesUserIdWhenTokenIsSent() throws Exception {
    mockMvc
        .perform(get("/api/v1/posts").headers(bearer(new AuthUser(9L, true, false))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.userId").value(9));
  }

  @Test
  @DisplayName("공개 경로에 토큰 없이 오면 인증 주체는 null 이다.")
  void principalIsNullWhenAnonymous() throws Exception {
    mockMvc
        .perform(get("/api/v1/posts"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.userId").doesNotExist())
        .andExpect(jsonPath("$.anonymous").value(true));
  }

  private HttpHeaders bearer(AuthUser authUser) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(tokenProvider.issueAccessToken(authUser));
    return headers;
  }
}
