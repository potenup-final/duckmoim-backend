package com.duckmoim.auth.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.duckmoim.auth.exception.AuthErrorCode;
import com.duckmoim.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;

/**
 * 카카오와 주고받는 <b>요청 두 번</b>을 본다 (AU-01).
 *
 * <p><b>진짜 카카오를 부르지 않는다.</b> 앱 열쇠가 사람 손을 기다리고 있고(계획서 6장), 열쇠가 있어도 인가코드는 한 번 쓰면 사라져서 테스트가 재현되지 않는다.
 * 그래서 카카오 자리에 {@code MockRestServiceServer} 를 세우고 <b>우리가 무엇을 보내고 무엇을 읽는지</b>를 검증한다.
 *
 * <p>여기서 확인하는 것은 <b>계약이다</b> — 어느 URL 에 어떤 폼을 보내는지, 두 번째 요청에 {@code Bearer} 를 붙이는지, 카카오가 거절하면 어떤 에러
 * 코드로 옮기는지. 회원이 생기는지는 {@code KakaoLoginServiceTest} 가 본다.
 */
@DisplayName("카카오 인가코드 교환")
class KakaoAuthClientTest {

  private static final String TOKEN_URI = "https://kauth.kakao.com/oauth/token";
  private static final String USER_URI = "https://kapi.kakao.com/v2/user/me";
  private static final String CLIENT_ID = "test-rest-api-key";
  private static final String REDIRECT_URI = "http://localhost:3000/auth/kakao/callback";
  private static final String TOKEN_ISSUED = "{\"access_token\":\"kakao-token\"}";

  private final RestClient.Builder builder = RestClient.builder();
  private final MockRestServiceServer kakao = MockRestServiceServer.bindTo(builder).build();

  @Test
  @DisplayName("인가코드를 보내면 카카오 회원번호가 온다.")
  void readKakaoUserId() {
    kakao
        .expect(requestTo(TOKEN_URI))
        .andExpect(method(HttpMethod.POST))
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
        .andExpect(content().string(containsString("grant_type=authorization_code")))
        .andExpect(content().string(containsString("client_id=" + CLIENT_ID)))
        .andExpect(content().string(containsString("code=authorization-code")))
        .andExpect(content().string(containsString("redirect_uri=http")))
        .andRespond(withSuccess(TOKEN_ISSUED, MediaType.APPLICATION_JSON));

    kakao
        .expect(requestTo(USER_URI))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header("Authorization", "Bearer kakao-token"))
        .andRespond(withSuccess("{\"id\":4321}", MediaType.APPLICATION_JSON));

    assertThat(client("").readKakaoUserId("authorization-code", REDIRECT_URI)).isEqualTo(4321L);

    kakao.verify();
  }

  /** 카카오 콘솔에서 「보안」을 켜지 않은 앱에 빈 시크릿을 보내면 요청이 거절된다. 그래서 값이 있을 때만 넣는다. */
  @Test
  @DisplayName("앱 시크릿이 비어 있으면 폼에 넣지 않는다.")
  void readKakaoUserId_withoutClientSecret() {
    kakao
        .expect(requestTo(TOKEN_URI))
        .andExpect(content().string(not(containsString("client_secret"))))
        .andRespond(withSuccess(TOKEN_ISSUED, MediaType.APPLICATION_JSON));
    expectUserLookup(withSuccess("{\"id\":1}", MediaType.APPLICATION_JSON));

    client("").readKakaoUserId("authorization-code", REDIRECT_URI);

    kakao.verify();
  }

  @Test
  @DisplayName("앱 시크릿이 설정돼 있으면 폼에 함께 보낸다.")
  void readKakaoUserId_withClientSecret() {
    kakao
        .expect(requestTo(TOKEN_URI))
        .andExpect(content().string(containsString("client_secret=app-secret")))
        .andRespond(withSuccess(TOKEN_ISSUED, MediaType.APPLICATION_JSON));
    expectUserLookup(withSuccess("{\"id\":1}", MediaType.APPLICATION_JSON));

    client("app-secret").readKakaoUserId("authorization-code", REDIRECT_URI);

    kakao.verify();
  }

  /**
   * 이미 쓴 인가코드를 다시 보내면 카카오가 KOE320 과 함께 400 을 준다.
   *
   * <p><b>사용자에게는 401 로 나간다.</b> 인가코드는 자격증명이고 한 번 쓰면 끝이라, 400 으로 「입력을 고쳐 다시 보내라」고 하면 고칠 것이 없다.
   */
  @Test
  @DisplayName("카카오가 인가코드를 거절하면 인가코드 오류로 옮긴다.")
  void readKakaoUserId_rejected() {
    kakao
        .expect(requestTo(TOKEN_URI))
        .andRespond(withStatus(HttpStatus.BAD_REQUEST).body("{\"error_code\":\"KOE320\"}"));

    assertThatThrownBy(() -> client("").readKakaoUserId("already-used-code", REDIRECT_URI))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", AuthErrorCode.AUTH_KAKAO_CODE_INVALID);
  }

  @Test
  @DisplayName("카카오가 장애를 내면 지연 오류로 옮긴다.")
  void readKakaoUserId_kakaoDown() {
    kakao.expect(requestTo(TOKEN_URI)).andRespond(withServerError());

    assertThatThrownBy(() -> client("").readKakaoUserId("authorization-code", REDIRECT_URI))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", AuthErrorCode.AUTH_KAKAO_UNAVAILABLE);
  }

  /**
   * 토큰 교환이 성공한 뒤의 실패다. <b>인가코드는 이미 유효한 것으로 판명됐다.</b>
   *
   * <p>그래서 「다시 로그인」이 아니라 「잠시 후」로 안내한다 — 코드를 새로 받아 와도 같은 자리에서 또 막힌다.
   */
  @Test
  @DisplayName("회원번호를 읽는 요청이 실패하면 인가코드 오류로 옮기지 않는다.")
  void readKakaoUserId_userLookupFailed() {
    kakao
        .expect(requestTo(TOKEN_URI))
        .andRespond(withSuccess(TOKEN_ISSUED, MediaType.APPLICATION_JSON));
    expectUserLookup(withStatus(HttpStatus.UNAUTHORIZED).body("{}"));

    assertThatThrownBy(() -> client("").readKakaoUserId("authorization-code", REDIRECT_URI))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", AuthErrorCode.AUTH_KAKAO_UNAVAILABLE);
  }

  /** 200 이어도 회원번호가 없으면 로그인시킬 수 없다. {@code null} 을 그대로 올려보내면 회원 조회에서 엉뚱한 실패가 난다. */
  @Test
  @DisplayName("카카오가 회원번호 없이 응답하면 지연 오류로 옮긴다.")
  void readKakaoUserId_withoutId() {
    kakao
        .expect(requestTo(TOKEN_URI))
        .andRespond(withSuccess(TOKEN_ISSUED, MediaType.APPLICATION_JSON));
    expectUserLookup(withSuccess("{}", MediaType.APPLICATION_JSON));

    assertThatThrownBy(() -> client("").readKakaoUserId("authorization-code", REDIRECT_URI))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", AuthErrorCode.AUTH_KAKAO_UNAVAILABLE);
  }

  private void expectUserLookup(ResponseCreator responseCreator) {
    kakao.expect(requestTo(USER_URI)).andRespond(responseCreator);
  }

  private KakaoAuthClient client(String clientSecret) {
    return new KakaoAuthClient(builder.build(), CLIENT_ID, clientSecret, TOKEN_URI, USER_URI);
  }
}
