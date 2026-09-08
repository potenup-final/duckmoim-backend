package com.duckmoim.auth.service;

import com.duckmoim.auth.infra.KakaoAuthClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * 카카오 로그인 (AU-01). <b>우리 서비스에 들어오는 유일한 문이다.</b>
 *
 * <p>검증 기준이 <i>"인가코드로 자체 토큰 발급까지 성공"</i> 한 줄이라, 이 메서드가 그 한 줄을 그대로 담는다 — 인가코드 → 회원번호 → 회원 → 토큰.
 *
 * <p><b>{@code @Transactional} 을 붙이지 않는다.</b> 카카오 호출 두 번이 트랜잭션 안에 들어가면 네트워크가 느린 만큼 DB 커넥션을 쥔다. 카카오가
 * 지연되는 순간 로그인 요청들이 커넥션 풀을 말리고, 그러면 <b>로그인과 무관한 요청까지</b> 함께 죽는다. DB 작업은 {@link KakaoSignupService} 와
 * {@link AuthService} 가 각자의 트랜잭션에서 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KakaoLoginService {

  private final KakaoAuthClient kakaoAuthClient;
  private final KakaoSignupService kakaoSignupService;
  private final AuthService authService;

  public AuthToken login(String authorizationCode, String redirectUri) {
    Long kakaoUserId = kakaoAuthClient.readKakaoUserId(authorizationCode, redirectUri);

    return authService.createTokens(findOrSignUp(kakaoUserId));
  }

  /**
   * <b>로그인 버튼을 두 번 누르면 첫 로그인 요청이 둘이 된다.</b> 둘 다 「없다」를 읽고 둘 다 INSERT 하면 {@code
   * uk_user_kakao_user_id} 에 걸려 한쪽이 500 이 된다 — 사용자에게는 「로그인이 되기도 하고 안 되기도 한다」로 보인다.
   *
   * <p>그때 <b>진 쪽은 다시 읽으면 된다.</b> 이긴 쪽이 방금 만든 행이 이미 있으므로 두 번째 시도는 조회로 끝난다. 재시도가 여기 있는 이유는 {@code
   * findOrSignUp} 의 트랜잭션이 위반으로 롤백돼서 <b>그 안에서는 다시 읽을 수 없기 때문이다</b> — 새 호출이 새 트랜잭션을 연다.
   *
   * <p>한 번만 다시 한다. 두 번째도 위반이면 동시 요청 문제가 아니라 다른 결함이므로 감추지 않고 올려보낸다.
   */
  private Long findOrSignUp(Long kakaoUserId) {
    try {
      return kakaoSignupService.findOrSignUp(kakaoUserId);
    } catch (DataIntegrityViolationException e) {
      log.warn("[KakaoLoginService.findOrSignUp] Concurrent first login. provider=KAKAO");
      return kakaoSignupService.findOrSignUp(kakaoUserId);
    }
  }
}
