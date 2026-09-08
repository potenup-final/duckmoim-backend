package com.duckmoim.auth.service;

import static com.duckmoim.identity.UserFixture.aUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.duckmoim.auth.domain.AuthUser;
import com.duckmoim.auth.domain.RefreshToken;
import com.duckmoim.auth.domain.TokenProvider;
import com.duckmoim.auth.exception.AuthErrorCode;
import com.duckmoim.auth.infra.RefreshTokenRepository;
import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.identity.domain.SignupStatus;
import com.duckmoim.identity.exception.UserErrorCode;
import com.duckmoim.identity.infra.UserRepository;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 재발급과 로그아웃 (AU-03 · AU-04).
 *
 * <p><b>{@code @Transactional} 을 쓰지 않는다.</b> 재사용 탐지가 「401 을 던지면서 폐기는 커밋한다」로 동작하는데(그래서 서비스가 {@code
 * noRollbackFor} 를 건다), 테스트에 롤백을 걸면 그 커밋이 사라져서 <b>무엇을 검증해도 통과한다.</b> 테스트 컨벤션 「테스트 데이터 정리」가 트랜잭션이
 * 개입하는 검증에 롤백을 쓰지 말라고 정한 이유가 이것이다. 대신 넣은 회원을 뒤에서 직접 지운다.
 */
@SpringBootTest
class AuthServiceTest {

  @Autowired private AuthService authService;
  @Autowired private TokenProvider tokenProvider;
  @Autowired private RefreshTokenRepository refreshTokenRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  @DisplayName("리프레시 토큰으로 재발급하면 새 토큰 쌍이 나온다.")
  void refresh() {
    long userId = aUser().insert(jdbcTemplate);
    AuthToken authToken = firstLogin(userId);

    AuthToken refreshed = authService.refresh(authToken.refreshToken());

    assertThat(refreshed.accessToken()).isNotBlank();
    assertThat(refreshed.refreshToken()).isNotEqualTo(authToken.refreshToken());
    cleanUp(userId);
  }

  /** 회전이다 — 새 것을 주면서 헌 것을 지운다. 이것이 재사용 탐지의 전부다. */
  @Test
  @DisplayName("재발급하면 쓰인 리프레시 토큰은 저장소에서 사라진다.")
  void refresh_removesUsedToken() {
    long userId = aUser().insert(jdbcTemplate);
    AuthToken authToken = firstLogin(userId);

    authService.refresh(authToken.refreshToken());

    assertThat(refreshTokenRepository.findByTokenHash(RefreshToken.hash(authToken.refreshToken())))
        .isEmpty();
    cleanUp(userId);
  }

  /** AU-03 의 검증 기준 앞쪽 — 「동일 Refresh 2회 사용 시 두 번째 거부」. */
  @Test
  @DisplayName("같은 리프레시 토큰을 두 번 쓰면 두 번째는 거부된다.")
  void refresh_reused() {
    long userId = aUser().insert(jdbcTemplate);
    AuthToken authToken = firstLogin(userId);
    authService.refresh(authToken.refreshToken());

    assertThatThrownBy(() -> authService.refresh(authToken.refreshToken()))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", AuthErrorCode.AUTH_REFRESH_TOKEN_INVALID);
    cleanUp(userId);
  }

  /**
   * AU-03 의 검증 기준 뒤쪽 — 「기존 세션 무효화」.
   *
   * <p>재사용을 탐지한 시점에 정상 사용자는 <b>이미 회전된 새 토큰</b>을 쥐고 있다. 그것까지 끊어야 훔친 쪽과 함께 로그아웃된다. 이 단언이 {@code
   * noRollbackFor} 를 지킨다 — 빼면 폐기가 롤백돼도 초록불이다.
   */
  @Test
  @DisplayName("재사용을 탐지하면 회전으로 받은 새 토큰까지 폐기된다.")
  void refresh_reusedInvalidatesEverySession() {
    long userId = aUser().insert(jdbcTemplate);
    AuthToken authToken = firstLogin(userId);
    AuthToken rotated = authService.refresh(authToken.refreshToken());

    assertThatThrownBy(() -> authService.refresh(authToken.refreshToken()))
        .isInstanceOf(BusinessException.class);

    assertThatThrownBy(() -> authService.refresh(rotated.refreshToken()))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", AuthErrorCode.AUTH_REFRESH_TOKEN_INVALID);
    cleanUp(userId);
  }

  @Test
  @DisplayName("재사용을 탐지하면 이미 발급된 액세스 토큰도 무효화된다.")
  void refresh_reusedInvalidatesIssuedAccessTokens() {
    long userId = aUser().insert(jdbcTemplate);
    AuthToken authToken = firstLogin(userId);
    authService.refresh(authToken.refreshToken());

    assertThatThrownBy(() -> authService.refresh(authToken.refreshToken()))
        .isInstanceOf(BusinessException.class);

    assertThat(invalidatedAt(userId)).isNotNull();
    cleanUp(userId);
  }

  /** 남의 것까지 끊으면 재사용 탐지 한 건이 전체 로그아웃이 된다. */
  @Test
  @DisplayName("재사용을 탐지해도 다른 회원의 세션은 남는다.")
  void refresh_reusedKeepsOtherUsers() {
    long userId = aUser().insert(jdbcTemplate);
    long otherUserId = aUser().insert(jdbcTemplate);
    AuthToken authToken = firstLogin(userId);
    AuthToken othersToken = firstLogin(otherUserId);
    authService.refresh(authToken.refreshToken());

    assertThatThrownBy(() -> authService.refresh(authToken.refreshToken()))
        .isInstanceOf(BusinessException.class);

    assertThat(authService.refresh(othersToken.refreshToken()).accessToken()).isNotBlank();
    cleanUp(userId);
    cleanUp(otherUserId);
  }

  @Test
  @DisplayName("액세스 토큰으로 재발급하면 거부된다.")
  void refresh_accessToken() {
    long userId = aUser().insert(jdbcTemplate);
    AuthToken authToken = firstLogin(userId);

    assertThatThrownBy(() -> authService.refresh(authToken.accessToken()))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", AuthErrorCode.AUTH_REFRESH_TOKEN_INVALID);
    cleanUp(userId);
  }

  /** AU-03 「재발급 성공 시 lastSeenAt 갱신」. 30분마다 재발급이 오므로 별도 핑 API 가 필요 없다. */
  @Test
  @DisplayName("재발급에 성공하면 마지막 접속 시각이 갱신된다.")
  void refresh_updatesLastSeenAt() {
    LocalDateTime longAgo = LocalDateTime.now(ZoneOffset.UTC).minusDays(30);
    long userId = aUser().lastSeenAt(longAgo).insert(jdbcTemplate);
    AuthToken authToken = firstLogin(userId);

    authService.refresh(authToken.refreshToken());

    assertThat(userRepository.findById(userId).orElseThrow().getLastSeenAt()).isAfter(longAgo);
    cleanUp(userId);
  }

  /** 가입을 마친 뒤 재발급하면 새 액세스 토큰이 그 사실을 담아야 한다. 리프레시에 담아 두면 14일 동안 낡은 값이 따라온다. */
  @Test
  @DisplayName("가입 미완료로 발급받았어도 가입을 마친 뒤 재발급하면 가입 완료로 나온다.")
  void refresh_readsSignupStatusAgain() {
    long userId = aUser().status(SignupStatus.PENDING_SIGNUP_INFO).insert(jdbcTemplate);
    AuthToken authToken = firstLogin(userId);
    jdbcTemplate.update("UPDATE user SET status = 'ACTIVE' WHERE id = ?", userId);

    AuthToken refreshed = authService.refresh(authToken.refreshToken());

    assertThat(tokenProvider.readAccessToken(refreshed.accessToken()).authUser())
        .isEqualTo(new AuthUser(userId, true, false));
    cleanUp(userId);
  }

  /** AU-04 의 검증 기준 — 「로그아웃 직후 기존 Access 로 401」의 근거다. */
  @Test
  @DisplayName("로그아웃하면 리프레시 토큰이 사라지고 액세스 토큰도 무효화된다.")
  void logout() {
    long userId = aUser().insert(jdbcTemplate);
    AuthToken authToken = firstLogin(userId);

    authService.logout(userId);

    assertThat(refreshTokenRepository.findByTokenHash(RefreshToken.hash(authToken.refreshToken())))
        .isEmpty();
    assertThat(invalidatedAt(userId)).isNotNull();
    cleanUp(userId);
  }

  @Test
  @DisplayName("로그아웃한 뒤에는 그 리프레시 토큰으로 재발급할 수 없다.")
  void logout_thenReissue() {
    long userId = aUser().insert(jdbcTemplate);
    AuthToken authToken = firstLogin(userId);
    authService.logout(userId);

    assertThatThrownBy(() -> authService.refresh(authToken.refreshToken()))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", AuthErrorCode.AUTH_REFRESH_TOKEN_INVALID);
    cleanUp(userId);
  }

  /**
   * <b>리뷰 1 — 재사용 탐지가 영구 잠금으로 번지지 않는다.</b>
   *
   * <p>죽은 Refresh 를 쥔 쪽이 공개 엔드포인트를 반복해서 때리는 상황이다. 폐기를 매번 다시 찍으면 무효화 시각이 앞으로 밀려, 그 사이에 정상 재로그인한 사용자의
   * 토큰까지 계속 끊긴다 — <b>복구 경로가 없어진다.</b>
   *
   * <p><b>초 경계를 한 번 넘긴다.</b> 훔친 토큰이 폐기보다 <b>이른 초</b>에 발급됐다는 것이 멱등 판정의 조건이고, 실제 탈취는 늘 그렇다(발급과 재사용
   * 사이에 분·시간이 흐른다). 테스트는 전부 같은 밀리초에 벌어져서 그 조건을 손으로 만들어야 한다.
   */
  @Test
  @DisplayName("이미 폐기된 리프레시 토큰을 다시 재사용해도 그 뒤 재로그인한 토큰은 살아 있다.")
  void refresh_reusedTwiceKeepsLaterLogin() throws InterruptedException {
    long userId = aUser().insert(jdbcTemplate);
    AuthToken stolen = firstLogin(userId);
    authService.refresh(stolen.refreshToken());
    Thread.sleep(1_050);

    assertThatThrownBy(() -> authService.refresh(stolen.refreshToken()))
        .isInstanceOf(BusinessException.class);

    AuthToken afterRelogin = authService.createTokens(userId);

    assertThatThrownBy(() -> authService.refresh(stolen.refreshToken()))
        .isInstanceOf(BusinessException.class);

    assertThat(authService.refresh(afterRelogin.refreshToken()).accessToken()).isNotBlank();
    cleanUp(userId);
  }

  /** 두 번째 폐기를 건너뛴다고 해서 <b>새로 훔친</b> 토큰의 재사용까지 놓치면 안 된다. */
  @Test
  @DisplayName("폐기 뒤에 발급된 토큰을 재사용하면 그때는 다시 폐기된다.")
  void refresh_reusedAfterInvalidationIsDetectedAgain() {
    long userId = aUser().insert(jdbcTemplate);
    AuthToken stolen = firstLogin(userId);
    authService.refresh(stolen.refreshToken());
    assertThatThrownBy(() -> authService.refresh(stolen.refreshToken()))
        .isInstanceOf(BusinessException.class);

    AuthToken fresh = authService.createTokens(userId);
    AuthToken rotated = authService.refresh(fresh.refreshToken());

    assertThatThrownBy(() -> authService.refresh(fresh.refreshToken()))
        .isInstanceOf(BusinessException.class);

    assertThatThrownBy(() -> authService.refresh(rotated.refreshToken()))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", AuthErrorCode.AUTH_REFRESH_TOKEN_INVALID);
    cleanUp(userId);
  }

  /**
   * <b>리뷰 2 — 같은 토큰으로 동시에 들어와도 500 이 나지 않는다.</b>
   *
   * <p>탭 둘이 동시에 재발급하는 흔한 상황이다. 「읽어서 있으면 지운다」로 짜면 둘 다 존재 검사를 지나고 진 쪽의 DELETE 가 0행이 되어 {@code
   * StaleStateException} → 500 이 난다. 회전을 {@code DELETE} 의 영향 행 수로 가르면 <b>정확히 하나만</b> 성공하고 나머지는
   * 재사용으로 판정된다.
   *
   * <p>{@code @Transactional} 을 쓰지 않는 클래스라 별도 스레드가 같은 트랜잭션에 끌려들지 않는다 (테스트 컨벤션 「테스트 데이터 정리」).
   */
  @Test
  @DisplayName("같은 리프레시 토큰으로 동시에 재발급하면 한 건만 성공하고 서버 오류가 없다.")
  void refresh_concurrent() throws Exception {
    long userId = aUser().insert(jdbcTemplate);
    String refreshToken = firstLogin(userId).refreshToken();

    int threads = 2;
    CountDownLatch start = new CountDownLatch(1);
    AtomicInteger success = new AtomicInteger();
    AtomicInteger rejected = new AtomicInteger();
    AtomicInteger serverError = new AtomicInteger();

    ExecutorService pool = Executors.newFixedThreadPool(threads);
    for (int i = 0; i < threads; i++) {
      pool.execute(
          () -> {
            try {
              start.await();
              authService.refresh(refreshToken);
              success.incrementAndGet();
            } catch (BusinessException e) {
              rejected.incrementAndGet();
            } catch (Exception e) {
              serverError.incrementAndGet();
            }
          });
    }
    start.countDown();
    pool.shutdown();
    assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

    assertThat(serverError).hasValue(0);
    assertThat(success).hasValue(1);
    assertThat(rejected).hasValue(threads - 1);
    cleanUp(userId);
  }

  /** <b>리뷰 5</b> — 로그인 경로에는 Refresh 토큰이 없어서 「다시 로그인」 코드가 거짓이 된다. */
  @Test
  @DisplayName("없는 회원으로 토큰을 발급하려 하면 회원을 찾을 수 없다고 알린다.")
  void createTokens_userNotFound() {
    assertThatThrownBy(() -> authService.createTokens(-1L))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", UserErrorCode.USER_NOT_FOUND);
  }

  /**
   * 최초 로그인이 하는 일이다.
   *
   * <p>실제 진입점은 카카오 로그인(AU-01)이고 E 티켓의 몫이지만, 발급 자체는 이 티켓의 AU-02 라 서비스의 공개 메서드를 그대로 부른다. 여기서 베껴 쓰면
   * {@code admin} 판정이나 Refresh 수명이 프로덕션과 조용히 갈라진다.
   */
  private AuthToken firstLogin(long userId) {
    return authService.createTokens(userId);
  }

  private LocalDateTime invalidatedAt(long userId) {
    return jdbcTemplate.queryForObject(
        "SELECT tokens_invalidated_at FROM user WHERE id = ?", LocalDateTime.class, userId);
  }

  /** SQL 로 지운다 — 파생 삭제는 호출한 쪽의 트랜잭션을 요구하고 이 테스트에는 그것이 없다. */
  private void cleanUp(long userId) {
    jdbcTemplate.update("DELETE FROM refresh_token WHERE user_id = ?", userId);
    jdbcTemplate.update("DELETE FROM user WHERE id = ?", userId);
  }
}
