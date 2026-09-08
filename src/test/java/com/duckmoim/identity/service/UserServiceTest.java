package com.duckmoim.identity.service;

import static com.duckmoim.identity.UserFixture.aUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 가입 정보 입력과 닉네임 중복 방지 (AU-05 · AU-06).
 *
 * <p><b>{@code @Transactional} 을 쓰지 않는다.</b> I-01 의 이중 방어가 DB 유니크 제약이라 진짜 제약이 걸린 상태로 돌아야 하고, 동시성
 * 테스트의 별도 스레드는 테스트의 트랜잭션에 참여하지 않는다 — 롤백을 걸면 <b>무엇을 검증해도 통과하는 상태</b>가 된다 (테스트 컨벤션 「테스트 데이터 정리」). 대신
 * 넣은 회원을 뒤에서 직접 지운다.
 *
 * <p><b>V11 시드의 닉네임 여섯을 피한다</b> — 방장덕후 · 댓글덕후 · 답글덕후 · 지나가던덕후 · 떠난덕후 · 운영자. 겹치면 유니크 제약에 걸려 검증하려던 것과
 * 무관한 실패가 난다.
 */
@SpringBootTest
class UserServiceTest {

  @Autowired private UserService userService;
  @Autowired private UserRepository userRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  /** AU-05 의 검증 기준 — 저장 후 재조회 시 동일 값. */
  @Test
  @DisplayName("가입 정보를 입력하면 닉네임과 출생연도가 그대로 저장된다.")
  void completeSignup() {
    long userId = pendingUser();

    userService.completeSignup(new SignupCommand(userId, "성수팝업러", 2000));

    User saved = userRepository.findById(userId).orElseThrow();
    assertThat(saved.getNickname()).isEqualTo("성수팝업러");
    assertThat(saved.getBirthYear().getValue()).isEqualTo(2000);
    assertThat(saved.isSignupCompleted()).isTrue();
    cleanUp(userId);
  }

  @Test
  @DisplayName("이미 쓰이는 닉네임으로 가입하면 거부된다.")
  void completeSignup_nicknameTaken() {
    long taken = aUser().nickname("잠실콘서트러").insert(jdbcTemplate);
    long userId = pendingUser();

    assertThatThrownBy(() -> userService.completeSignup(new SignupCommand(userId, "잠실콘서트러", 2000)))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", UserErrorCode.USER_NICKNAME_DUPLICATED);
    cleanUp(userId);
    cleanUp(taken);
  }

  @Test
  @DisplayName("만 14세 미만이면 가입이 거부된다.")
  void completeSignup_underMinimumAge() {
    long userId = pendingUser();
    int tooYoung = java.time.LocalDate.now().getYear() - 14;

    assertThatThrownBy(
            () -> userService.completeSignup(new SignupCommand(userId, "어린덕후", tooYoung)))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", UserErrorCode.USER_UNDER_MINIMUM_AGE);
    cleanUp(userId);
  }

  @Test
  @DisplayName("가입을 마친 계정이 다시 입력하면 거부된다.")
  void completeSignup_alreadySet() {
    long userId = aUser().nickname("이미가입한덕후").insert(jdbcTemplate);

    assertThatThrownBy(() -> userService.completeSignup(new SignupCommand(userId, "다른이름덕후", 2000)))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", UserErrorCode.USER_SIGNUP_INFO_ALREADY_SET);
    cleanUp(userId);
  }

  /**
   * <b>이중 제출의 두 번째 요청이다.</b> 버튼을 두 번 누르면 두 요청의 닉네임이 <b>같다</b> — 두 번째는 방금 자기가 등록한 이름을 다시 낸다.
   *
   * <p>위 테스트가 다른 닉네임을 내므로 이 경우를 덮지 못한다.
   */
  @Test
  @DisplayName("가입을 마친 계정이 자기 닉네임을 다시 내도 가입 완료로 거부된다.")
  void completeSignup_alreadySetWithOwnNickname() {
    long userId = pendingUser();
    userService.completeSignup(new SignupCommand(userId, "두번누른덕후", 2000));

    assertThatThrownBy(() -> userService.completeSignup(new SignupCommand(userId, "두번누른덕후", 2000)))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", UserErrorCode.USER_SIGNUP_INFO_ALREADY_SET);
    cleanUp(userId);
  }

  /** 조건 둘이 동시에 걸릴 때 <b>상태가 입력값을 이긴다</b> — 요청 자체가 허용되지 않으므로 출생연도를 볼 이유가 없다. */
  @Test
  @DisplayName("가입을 마친 계정이 연령 미달 연도를 내면 연령이 아니라 가입 완료로 거부된다.")
  void completeSignup_alreadySetBeatsAge() {
    long userId = aUser().nickname("연도까지틀린덕후").insert(jdbcTemplate);
    int tooYoung = java.time.LocalDate.now().getYear() - 14;

    assertThatThrownBy(
            () -> userService.completeSignup(new SignupCommand(userId, "새이름덕후", tooYoung)))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", UserErrorCode.USER_SIGNUP_INFO_ALREADY_SET);
    cleanUp(userId);
  }

  @Test
  @DisplayName("탈퇴한 계정으로 가입 정보를 입력하면 회원을 찾을 수 없다.")
  void completeSignup_withdrawn() {
    long userId = aUser().status(SignupStatus.WITHDRAWN).insert(jdbcTemplate);

    assertThatThrownBy(() -> userService.completeSignup(new SignupCommand(userId, "돌아온덕후", 2000)))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", UserErrorCode.USER_NOT_FOUND);
    cleanUp(userId);
  }

  /**
   * <b>AU-06 의 검증 기준 그 자체 — 동시 요청 2건 중 1건만 성공, 나머지 409.</b>
   *
   * <p>서로 다른 두 회원이 같은 닉네임을 동시에 낸다. <b>사전 조회는 둘 다 통과한다</b> — 그래서 API 설계가 「확정은 아니다」로 적어 뒀다. 실제 차단은
   * {@code uk_user_nickname} 이 하고, service 가 그 위반을 409 로 옮긴다 (I-01 의 이중 방어).
   *
   * <p><b>500 이 하나라도 나면 실패다.</b> 제약 위반을 그대로 흘리면 사용자는 원인을 알 수 없는 서버 오류를 본다.
   */
  @Test
  @DisplayName("같은 닉네임으로 동시에 가입하면 한 건만 성공하고 나머지는 중복으로 거부된다.")
  void completeSignup_concurrent() throws Exception {
    long first = pendingUser();
    long second = pendingUser();
    String contested = "동시에노린이름";

    CountDownLatch start = new CountDownLatch(1);
    AtomicInteger success = new AtomicInteger();
    AtomicInteger duplicated = new AtomicInteger();
    AtomicInteger serverError = new AtomicInteger();

    ExecutorService pool = Executors.newFixedThreadPool(2);
    for (long userId : new long[] {first, second}) {
      pool.execute(
          () -> {
            try {
              start.await();
              userService.completeSignup(new SignupCommand(userId, contested, 2000));
              success.incrementAndGet();
            } catch (BusinessException e) {
              if (e.getErrorCode() == UserErrorCode.USER_NICKNAME_DUPLICATED) {
                duplicated.incrementAndGet();
              } else {
                serverError.incrementAndGet();
              }
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
    assertThat(duplicated).hasValue(1);
    cleanUp(first);
    cleanUp(second);
  }

  private long pendingUser() {
    return aUser().status(SignupStatus.PENDING_SIGNUP_INFO).insert(jdbcTemplate);
  }

  private void cleanUp(long userId) {
    jdbcTemplate.update("DELETE FROM refresh_token WHERE user_id = ?", userId);
    jdbcTemplate.update("DELETE FROM user WHERE id = ?", userId);
  }
}
