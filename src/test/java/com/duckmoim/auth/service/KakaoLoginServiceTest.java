package com.duckmoim.auth.service;

import static com.duckmoim.identity.UserFixture.aUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.duckmoim.auth.domain.TokenProvider;
import com.duckmoim.auth.infra.KakaoAuthClient;
import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.identity.domain.SignupStatus;
import com.duckmoim.identity.domain.User;
import com.duckmoim.identity.exception.UserErrorCode;
import com.duckmoim.identity.infra.UserRepository;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 카카오 로그인의 <b>회원 쪽</b>을 본다 (AU-01).
 *
 * <p>검증 기준이 <i>"인가코드로 자체 토큰 발급까지 성공"</i> 한 줄인데, 그 한 줄 안에 「최초 로그인 시 자동 가입, 가입 정보 미입력 상태로 진입」이 함께 들어
 * 있다. 그래서 <b>토큰이 나오는지</b>와 <b>회원이 어떤 상태로 생기는지</b>를 같이 확인한다.
 *
 * <p><b>카카오만 대역으로 바꾼다.</b> 인가코드를 회원번호로 바꾸는 일은 {@code KakaoAuthClientTest} 가 실제 HTTP 계약으로 검증했다. 여기서는
 * 그 뒤에 일어나는 일 — 조회 · 자동 가입 · 토큰 발급 — 을 <b>진짜 DB</b> 로 본다.
 *
 * <p><b>{@code @Transactional} 을 쓰지 않는다.</b> 동시 첫 로그인의 차단이 {@code uk_user_kakao_user_id} 이고, 별도
 * 스레드는 테스트의 트랜잭션에 참여하지 않는다 — 롤백을 걸면 <b>무엇을 검증해도 통과하는 상태</b>가 된다 (테스트 컨벤션 「테스트 데이터 정리」). 대신 넣은 회원을
 * 뒤에서 직접 지운다.
 *
 * <p>회원번호는 {@code UserFixture} 가 쓰는 대역(10만 대)과 겹치지 않게 900만 대에서 뽑는다. 겹치면 유니크 제약에 걸려 검증하려던 것과 무관한 실패가
 * 난다.
 */
@SpringBootTest
@DisplayName("카카오 로그인")
class KakaoLoginServiceTest {

  private static final AtomicLong KAKAO_USER_ID = new AtomicLong(9_000_000);
  private static final String REDIRECT_URI = "http://localhost:3000/auth/kakao/callback";

  @Autowired private KakaoLoginService kakaoLoginService;
  @Autowired private UserRepository userRepository;
  @Autowired private TokenProvider tokenProvider;
  @Autowired private JdbcTemplate jdbcTemplate;

  @MockitoBean private KakaoAuthClient kakaoAuthClient;

  /** AU-01 의 검증 기준 — 인가코드로 자체 토큰 발급까지 성공. */
  @Test
  @DisplayName("처음 로그인하면 계정이 만들어지고 토큰이 발급된다.")
  void login_firstTime() {
    long kakaoUserId = kakaoResponds();

    AuthToken authToken = kakaoLoginService.login("authorization-code", REDIRECT_URI);

    assertThat(authToken.accessToken()).isNotEmpty();
    assertThat(authToken.refreshToken()).isNotEmpty();
    assertThat(userRepository.findByKakaoUserId(kakaoUserId)).isPresent();
    cleanUp(kakaoUserId);
  }

  /**
   * 「가입 정보 미입력 상태로 진입」이 이것이다 (AU-01).
   *
   * <p>카카오에서 받는 것은 회원번호뿐이라(결정 D-2) 닉네임을 채울 재료가 없고, 채우면 사용자가 고를 기회도 없이 I-01(닉네임 유일)과 부딪힌다.
   */
  @Test
  @DisplayName("처음 로그인으로 만들어진 계정은 닉네임과 출생연도가 비어 있다.")
  void login_firstTimeLeavesSignupInfoEmpty() {
    long kakaoUserId = kakaoResponds();

    kakaoLoginService.login("authorization-code", REDIRECT_URI);

    User created = userRepository.findByKakaoUserId(kakaoUserId).orElseThrow();
    assertThat(created.isSignupPending()).isTrue();
    assertThat(created.getNickname()).isNull();
    assertThat(created.getBirthYear()).isNull();
    cleanUp(kakaoUserId);
  }

  /** 두 번째 로그인은 <b>가입이 아니다.</b> 계정이 늘면 같은 사람이 둘로 갈라져 모집글과 댓글이 흩어진다. */
  @Test
  @DisplayName("다시 로그인하면 같은 계정에 붙는다.")
  void login_returning() {
    long kakaoUserId = kakaoResponds();
    long firstUserId = userIdOf(kakaoLoginService.login("first-code", REDIRECT_URI));

    long secondUserId = userIdOf(kakaoLoginService.login("second-code", REDIRECT_URI));

    assertThat(secondUserId).isEqualTo(firstUserId);
    assertThat(countOf(kakaoUserId)).isEqualTo(1);
    cleanUp(kakaoUserId);
  }

  /** 클라이언트가 가입 화면으로 보낼지 판단하는 유일한 근거다 (API 설계 2-1). */
  @Test
  @DisplayName("가입을 마친 회원이 로그인하면 가입 완료 여부가 참으로 온다.")
  void login_signupCompleted() {
    long kakaoUserId = kakaoResponds();
    aUser().kakaoUserId(kakaoUserId).insert(jdbcTemplate);

    AuthToken authToken = kakaoLoginService.login("authorization-code", REDIRECT_URI);

    assertThat(authToken.signupCompleted()).isTrue();
    cleanUp(kakaoUserId);
  }

  @Test
  @DisplayName("가입을 마치지 않은 회원이 로그인하면 가입 완료 여부가 거짓으로 온다.")
  void login_signupIncomplete() {
    long kakaoUserId = kakaoResponds();

    AuthToken authToken = kakaoLoginService.login("authorization-code", REDIRECT_URI);

    assertThat(authToken.signupCompleted()).isFalse();
    cleanUp(kakaoUserId);
  }

  /**
   * <b>탈퇴 계정을 새로 가입시킬 수 없다.</b> 탈퇴가 소프트 삭제라 행이 남고 {@code kakao_user_id} 유니크 제약도 그 행을 센다.
   *
   * <p>그래서 조회는 탈퇴 계정도 찾아 주고, 거절은 토큰을 만드는 쪽이 한다. 「탈퇴 후 재가입」을 열려면 AU-11 이 그 정책을 정해야 한다.
   */
  @Test
  @DisplayName("탈퇴한 회원의 회원번호로 로그인하면 회원을 찾을 수 없다.")
  void login_withdrawn() {
    long kakaoUserId = kakaoResponds();
    aUser().kakaoUserId(kakaoUserId).status(SignupStatus.WITHDRAWN).insert(jdbcTemplate);

    assertThatThrownBy(() -> kakaoLoginService.login("authorization-code", REDIRECT_URI))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", UserErrorCode.USER_NOT_FOUND);
    cleanUp(kakaoUserId);
  }

  /**
   * <b>로그인 버튼을 두 번 누르면 첫 로그인 요청이 둘이 된다.</b>
   *
   * <p>둘 다 「없다」를 읽고 둘 다 INSERT 하면 {@code uk_user_kakao_user_id} 에 걸려 <b>한쪽이 500</b> 이 된다. 진 쪽이 새
   * 트랜잭션에서 다시 읽으면 이긴 쪽이 만든 계정이 이미 있어 그대로 로그인된다.
   *
   * <p><b>500 이 하나라도 나면 실패다.</b> 그리고 계정은 반드시 하나여야 한다 — 둘이 생기면 유니크 제약이 없다는 뜻이다.
   */
  @Test
  @DisplayName("같은 회원번호로 동시에 처음 로그인해도 계정은 하나만 생긴다.")
  void login_concurrentFirstLogin() throws Exception {
    long kakaoUserId = kakaoResponds();

    CountDownLatch start = new CountDownLatch(1);
    AtomicInteger success = new AtomicInteger();
    AtomicInteger failure = new AtomicInteger();

    ExecutorService pool = Executors.newFixedThreadPool(2);
    for (int attempt = 0; attempt < 2; attempt++) {
      pool.execute(
          () -> {
            try {
              start.await();
              kakaoLoginService.login("authorization-code", REDIRECT_URI);
              success.incrementAndGet();
            } catch (Exception e) {
              failure.incrementAndGet();
            }
          });
    }
    start.countDown();
    pool.shutdown();
    assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

    assertThat(failure).hasValue(0);
    assertThat(success).hasValue(2);
    assertThat(countOf(kakaoUserId)).isEqualTo(1);
    cleanUp(kakaoUserId);
  }

  /** 카카오가 이 회원번호를 돌려주도록 세운다. 돌려준 값은 뒤에서 행을 확인하고 지우는 데 쓴다. */
  private long kakaoResponds() {
    long kakaoUserId = KAKAO_USER_ID.getAndIncrement();
    given(kakaoAuthClient.readKakaoUserId(any(), any())).willReturn(kakaoUserId);
    return kakaoUserId;
  }

  /** 클라이언트가 실제로 받는 값에서 읽는다 — 토큰의 {@code sub} 가 그 회원번호다. */
  private long userIdOf(AuthToken authToken) {
    return tokenProvider.readAccessToken(authToken.accessToken()).authUser().userId();
  }

  private int countOf(long kakaoUserId) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM user WHERE kakao_user_id = ?", Integer.class, kakaoUserId);
  }

  private void cleanUp(long kakaoUserId) {
    jdbcTemplate.update(
        "DELETE FROM refresh_token WHERE user_id IN (SELECT id FROM user WHERE kakao_user_id = ?)",
        kakaoUserId);
    jdbcTemplate.update("DELETE FROM user WHERE kakao_user_id = ?", kakaoUserId);
  }
}
