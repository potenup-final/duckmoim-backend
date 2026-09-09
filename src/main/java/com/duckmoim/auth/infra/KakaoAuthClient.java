package com.duckmoim.auth.infra;

import com.duckmoim.auth.exception.AuthErrorCode;
import com.duckmoim.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * 인가코드를 카카오 회원번호로 바꾼다 (AU-01).
 *
 * <p><b>{@code spring-boot-starter-oauth2-client} 를 쓰지 않는다.</b> 그 스타터는 서버가 브라우저를 카카오로 리다이렉트시키고 콜백을
 * 받는 흐름을 전제한다. 우리 계약은 반대다 — <b>프론트가 인가코드를 받아 body 로 넘긴다</b> (API 설계 2-1). 그래서 백엔드가 할 일은 HTTP 요청 두
 * 번이고, 그 두 번을 위해 필터 체인과 세션 저장소를 끌고 오지 않는다.
 *
 * <p><b>왜 두 번인가</b> — 카카오는 인가코드를 회원번호로 곧바로 바꿔 주지 않는다. 인가코드로 카카오 토큰을 받고(①), 그 토큰으로 회원번호를 읽는다(②). ① 의
 * 토큰은 ② 에서만 쓰고 버린다 (결정 D-2) — 저장하지 않으므로 갱신 · 폐기를 다룰 일이 없다.
 *
 * <p><b>회원번호를 로그에 남기지 않는다.</b> 코드 컨벤션이 인가코드 · 토큰 · 카카오 회원번호를 로그 금지 값으로 못박았다. 실패 로그에 {@code
 * provider=KAKAO} 와 상태 코드만 남기는 이유다.
 */
@Slf4j
@Component
public class KakaoAuthClient {

  private final RestClient restClient;
  private final String clientId;
  private final String clientSecret;
  private final String tokenUri;
  private final String userUri;

  public KakaoAuthClient(
      RestClient kakaoRestClient,
      @Value("${duckmoim.kakao.client-id}") String clientId,
      @Value("${duckmoim.kakao.client-secret}") String clientSecret,
      @Value("${duckmoim.kakao.token-uri}") String tokenUri,
      @Value("${duckmoim.kakao.user-uri}") String userUri) {
    this.restClient = kakaoRestClient;
    this.clientId = clientId;
    this.clientSecret = clientSecret;
    this.tokenUri = tokenUri;
    this.userUri = userUri;
  }

  /**
   * 인가코드로 카카오 회원번호를 읽는다.
   *
   * <p>이름에 {@code Kakao} 를 남겨 둔다. 돌려주는 값은 <b>우리 {@code user.id} 가 아니라</b> 카카오가 매긴 번호라, 호출부에서 둘이 섞이면
   * 남의 계정으로 토큰을 발급하는 사고가 된다.
   *
   * <p><b>{@code redirectUri} 는 클라이언트가 인가 때 쓴 값이다.</b> 카카오가 그 값과의 일치를 강제하므로 서버가 임의로 정할 수 없다 — 브라우저
   * {@code origin} 이 로컬 · 프리뷰 · 프로덕션에서 각각 다르다.
   */
  public Long readKakaoUserId(String authorizationCode, String redirectUri) {
    return readUserId(exchange(authorizationCode, redirectUri));
  }

  /**
   * ① 인가코드 → 카카오 토큰.
   *
   * <p><b>4xx 만 {@code AUTH_KAKAO_CODE_INVALID} 로 옮긴다.</b> 이 요청의 4xx 는 사실상 인가코드 문제다 — 무효 · 만료 · 이미
   * 사용됨(KOE320). 5xx 와 통신 실패는 사용자가 고칠 수 있는 것이 없으므로 갈라서 낸다.
   *
   * <p>앱 시크릿은 <b>켜져 있을 때만</b> 보낸다. 카카오 콘솔에서 「보안」을 켜지 않은 앱에 빈 {@code client_secret} 을 보내면 요청이 거절된다.
   */
  private String exchange(String authorizationCode, String redirectUri) {
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("grant_type", "authorization_code");
    form.add("client_id", clientId);
    form.add("redirect_uri", redirectUri);
    form.add("code", authorizationCode);
    if (StringUtils.hasText(clientSecret)) {
      form.add("client_secret", clientSecret);
    }

    KakaoTokenResponse response;
    try {
      response =
          restClient
              .post()
              .uri(tokenUri)
              .contentType(MediaType.APPLICATION_FORM_URLENCODED)
              .body(form)
              .retrieve()
              .body(KakaoTokenResponse.class);
    } catch (RestClientResponseException e) {
      throw rejected(e);
    } catch (RestClientException e) {
      log.error("[KakaoAuthClient.exchange] Failed to reach Kakao. provider=KAKAO", e);
      throw new BusinessException(AuthErrorCode.AUTH_KAKAO_UNAVAILABLE);
    }

    if (response == null || !StringUtils.hasText(response.accessToken())) {
      log.error("[KakaoAuthClient.exchange] Kakao returned no token. provider=KAKAO");
      throw new BusinessException(AuthErrorCode.AUTH_KAKAO_UNAVAILABLE);
    }
    return response.accessToken();
  }

  /**
   * ② 카카오 토큰 → 회원번호.
   *
   * <p><b>여기의 4xx 는 인가코드 문제가 아니다.</b> ① 이 성공했다는 것은 코드가 유효했다는 뜻이므로, 이 자리의 실패는 우리 앱 설정이나 카카오 쪽 사정이다.
   * 그래서 상태 코드를 가리지 않고 {@code AUTH_KAKAO_UNAVAILABLE} 로 낸다 — 「다시 로그인」을 안내해도 같은 자리에서 또 막힌다.
   */
  private Long readUserId(String kakaoToken) {
    KakaoUserResponse response;
    try {
      response =
          restClient
              .get()
              .uri(userUri)
              .header("Authorization", "Bearer " + kakaoToken)
              .retrieve()
              .body(KakaoUserResponse.class);
    } catch (RestClientException e) {
      log.error("[KakaoAuthClient.readUserId] Failed to read Kakao user. provider=KAKAO", e);
      throw new BusinessException(AuthErrorCode.AUTH_KAKAO_UNAVAILABLE);
    }

    if (response == null || response.id() == null) {
      log.error("[KakaoAuthClient.readUserId] Kakao returned no user id. provider=KAKAO");
      throw new BusinessException(AuthErrorCode.AUTH_KAKAO_UNAVAILABLE);
    }
    return response.id();
  }

  /** 상태 코드만 남긴다. 카카오 응답 본문에는 우리가 보낸 인가코드가 되돌아올 수 있다. */
  private BusinessException rejected(RestClientResponseException e) {
    if (e.getStatusCode().is4xxClientError()) {
      log.warn(
          "[KakaoAuthClient.exchange] Kakao rejected the authorization. provider=KAKAO, status={}",
          e.getStatusCode().value());
      return new BusinessException(AuthErrorCode.AUTH_KAKAO_CODE_INVALID);
    }

    log.error(
        "[KakaoAuthClient.exchange] Kakao failed to issue a token. provider=KAKAO, status={}",
        e.getStatusCode().value());
    return new BusinessException(AuthErrorCode.AUTH_KAKAO_UNAVAILABLE);
  }
}
