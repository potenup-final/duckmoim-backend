package com.duckmoim.auth.service;

import com.duckmoim.identity.domain.User;
import com.duckmoim.identity.infra.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 카카오 회원번호에 우리 회원 행을 맞춰 준다 — 「최초 로그인 시 자동 가입」 (AU-01).
 *
 * <p><b>왜 {@code KakaoLoginService} 와 빈이 갈렸는가</b> — 트랜잭션 경계가 서로 달라야 한다.
 *
 * <ul>
 *   <li>카카오 호출은 <b>트랜잭션 밖</b>이어야 한다. 네트워크가 느린 만큼 DB 커넥션을 쥐면 안 된다
 *   <li>이 저장은 <b>트랜잭션 안</b>이어야 한다. 그리고 제약 위반으로 실패하면 <b>새 트랜잭션</b>에서 다시 읽어야 한다 — 위반이 터진 트랜잭션은 롤백만
 *       남아서 같은 트랜잭션 안에서 재조회할 수 없다
 * </ul>
 *
 * <p>한 빈의 메서드 둘로 나누면 두 번째 조건이 성립하지 않는다. 자기 자신을 부르는 호출은 프록시를 지나지 않아 <b>트랜잭션이 새로 열리지 않는다.</b>
 *
 * <p><b>왜 {@code identity.service} 가 아닌가</b> — 게이트({@code LAYER_DEPENDENCY})가 service 를
 * presentation 에서만 참조하게 막아서, {@code auth} 의 서비스가 {@code identity} 의 서비스를 부를 수 없다. 저장소는 부를 수 있으므로
 * 여기서 {@link UserRepository} 를 쓴다 — {@code AuthService} 가 이미 같은 방향으로 서 있고 그 근거를 PR 에 적어 두었다.
 */
@Service
@RequiredArgsConstructor
public class KakaoSignupService {

  private final UserRepository userRepository;

  /**
   * 회원번호로 찾고 없으면 만든다. 돌려주는 것은 <b>우리 {@code user.id}</b> 다.
   *
   * <p><b>{@code saveAndFlush} 를 쓴다.</b> {@code save} 만 하면 INSERT 가 커밋까지 미뤄져 유니크 위반이 이 메서드 밖에서 터진다.
   * 그러면 부르는 쪽이 「동시 첫 로그인」인지 다른 실패인지 가릴 자리를 놓친다.
   *
   * <p><b>탈퇴 계정도 그대로 돌려준다.</b> 걸러내면 새로 만들려 들고 그 순간 {@code kakao_user_id} 유니크 제약에 걸린다. 로그인 거절은 토큰을
   * 만드는 쪽이 판정한다 ({@code AuthService.lockUser} → {@code USER_NOT_FOUND}).
   */
  @Transactional
  public Long findOrSignUp(Long kakaoUserId) {
    return userRepository
        .findByKakaoUserId(kakaoUserId)
        .orElseGet(() -> userRepository.saveAndFlush(User.signUp(kakaoUserId)))
        .getId();
  }
}
