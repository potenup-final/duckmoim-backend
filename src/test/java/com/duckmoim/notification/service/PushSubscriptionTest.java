package com.duckmoim.notification.service;

import static com.duckmoim.identity.UserFixture.aUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.identity.domain.UserWithdrawn;
import com.duckmoim.identity.service.UserService;
import com.duckmoim.notification.domain.PushSubscription;
import com.duckmoim.notification.exception.NotificationErrorCode;
import com.duckmoim.notification.infra.PushSubscriptionRepository;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * 구독 등록과 해제 (NT-12).
 *
 * <p><b>검증 기준이 「기기 둘에서 등록하면 둘 다 받는다」다.</b> 실제로 받는 것은 발송(NT-13)이 하고, 여기서는 <b>둘이 남는지</b>까지 본다 — 그 아래는
 * 발송이 이 목록을 그대로 도는 것이라 따라온다.
 *
 * <p><b>실제 MySQL 로 돈다.</b> 이 기능의 규칙이 유니크 제약과 upsert 에 들어 있어 mock 으로는 아무것도 검증되지 않는다.
 */
@SpringBootTest
@Transactional
@DisplayName("웹 푸시 구독")
class PushSubscriptionTest {

  private static final long ME = 880001L;
  private static final long OTHER = 880002L;

  private static final String PHONE = "https://fcm.googleapis.com/fcm/send/phone-token";
  private static final String LAPTOP = "https://updates.push.services.mozilla.com/wpush/v2/laptop";
  private static final String TABLET = "https://web.push.apple.com/tablet";

  @Autowired private PushSubscriptionService pushSubscriptionService;
  @Autowired private PushSubscriptionRepository pushSubscriptionRepository;
  @Autowired private UserService userService;
  @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

  /** <b>이 검사가 NT-12 의 검증 기준이다.</b> */
  @DisplayName("기기 둘에서 등록하면 둘 다 남는다.")
  @Test
  void register_keepsEveryDevice() {
    // when
    pushSubscriptionService.register(ME, PHONE, "phone-key", "phone-auth");
    pushSubscriptionService.register(ME, LAPTOP, "laptop-key", "laptop-auth");

    // then
    assertThat(endpointsOf(ME)).containsExactlyInAnyOrder(PHONE, LAPTOP);
  }

  /**
   * 브라우저가 구독을 갈 때마다 다시 보내는 것이 정상 경로다 ({@code pushsubscriptionchange}).
   *
   * <p>행이 쌓이면 <b>한 기기에 같은 알림이 여러 번 간다.</b>
   */
  @DisplayName("같은 기기가 다시 등록해도 한 건이고 키가 갱신된다.")
  @Test
  void register_updatesInsteadOfPilingUp() {
    // given
    pushSubscriptionService.register(ME, PHONE, "old-key", "old-auth");

    // when
    pushSubscriptionService.register(ME, PHONE, "new-key", "new-auth");

    // then
    assertThat(subscriptionsOf(ME))
        .singleElement()
        .satisfies(
            subscription -> {
              assertThat(subscription.getP256dh()).isEqualTo("new-key");
              assertThat(subscription.getAuth()).isEqualTo("new-auth");
            });
  }

  /**
   * 공용 기기에서 사람이 바뀌는 경우다.
   *
   * <p><b>주인을 안 옮기면 앞사람의 알림이 뒷사람 기기로 간다.</b> 그래서 유니크가 유저별이 아니라 전역이다 (V808).
   */
  @DisplayName("같은 기기를 남이 등록하면 주인이 옮겨간다.")
  @Test
  void register_movesOwnerOnSharedDevice() {
    // given
    pushSubscriptionService.register(ME, PHONE, "key", "auth");

    // when
    pushSubscriptionService.register(OTHER, PHONE, "key", "auth");

    // then
    assertThat(endpointsOf(ME)).isEmpty();
    assertThat(endpointsOf(OTHER)).containsExactly(PHONE);
  }

  /** 「기기 둘에서 등록하면 둘 다 받는다」가 요구사항이라 한 기기만 끄는 길이 있어야 한다. */
  @DisplayName("해제하면 그 기기만 빠진다.")
  @Test
  void unregister_removesOnlyThatDevice() {
    // given
    pushSubscriptionService.register(ME, PHONE, "phone-key", "phone-auth");
    pushSubscriptionService.register(ME, LAPTOP, "laptop-key", "laptop-auth");

    // when
    pushSubscriptionService.unregister(ME, PHONE);

    // then
    assertThat(endpointsOf(ME)).containsExactly(LAPTOP);
  }

  /** 주소는 비밀이 아니다. 공용 기기를 쓴 사람은 그 값을 본 적이 있다. */
  @DisplayName("남의 기기는 끊을 수 없다.")
  @Test
  void unregister_cannotTouchSomeoneElse() {
    // given
    pushSubscriptionService.register(OTHER, PHONE, "key", "auth");

    // when
    pushSubscriptionService.unregister(ME, PHONE);

    // then
    assertThat(endpointsOf(OTHER)).containsExactly(PHONE);
  }

  /** 화면에서 알림을 끄는 동작이라 몇 번을 눌러도 같은 결과여야 한다. */
  @DisplayName("없는 구독을 지워도 성공이다.")
  @Test
  void unregister_isIdempotent() {
    // when & then — 예외가 나지 않는다
    pushSubscriptionService.unregister(ME, PHONE);

    assertThat(endpointsOf(ME)).isEmpty();
  }

  /**
   * <b>안 지우면 탈퇴한 사람 폰에 알림이 뜬다.</b>
   *
   * <p>탈퇴는 토큰만 끊고 행은 남기는 소프트 삭제라, 그 사람의 옛 댓글에 답글이 달리면 알림이 그대로 발행된다. 인앱은 로그인이 막혀 아무도 못 보지만 푸시는 로그인
   * 없이 기기에 직접 닿는다 — 이 티켓이 새로 여는 노출이다.
   */
  @DisplayName("탈퇴하면 그 사람의 구독이 전부 지워진다.")
  @Test
  void forgetAll_clearsEveryDeviceOnWithdrawal() {
    // given
    pushSubscriptionService.register(ME, PHONE, "phone-key", "phone-auth");
    pushSubscriptionService.register(ME, LAPTOP, "laptop-key", "laptop-auth");
    pushSubscriptionService.register(OTHER, TABLET, "key", "auth");

    // when
    pushSubscriptionService.forgetAll(new UserWithdrawn(ME));

    // then — 남의 것은 그대로다
    assertThat(endpointsOf(ME)).isEmpty();
    assertThat(endpointsOf(OTHER)).hasSize(1);
  }

  /**
   * <b>배선까지 본다.</b> 위 검사는 리스너를 직접 불러서 「지운다」만 보이는데, 탈퇴가 그 사실을 실제로 발행하지 않으면 운영에서는 아무 일도 일어나지 않는다.
   *
   * <p>{@code MANDATORY} 라 탈퇴 트랜잭션에 올라탄다 — 따로 커밋되지 않는다.
   */
  @DisplayName("탈퇴 명령이 구독 정리까지 이어진다.")
  @Test
  void withdraw_clearsSubscriptions() {
    // given
    long userId =
        aUser()
            .nickname("탈퇴자" + java.util.UUID.randomUUID().toString().substring(0, 8))
            .insert(jdbc);
    pushSubscriptionService.register(userId, PHONE, "key", "auth");

    // when
    userService.withdraw(userId);

    // then
    assertThat(endpointsOf(userId)).isEmpty();
  }

  /**
   * <b>이 값은 브라우저가 만들지만 요청 본문으로 들어온다</b> (PR #157 리뷰).
   *
   * <p>막지 않으면 워커가 그 주소로 POST 를 보낸다 — 본문이 안 돌아와도 상태 코드가 갈려 내부 스캔이 된다.
   */
  @DisplayName("알려진 푸시 서비스가 아닌 주소는 등록되지 않는다.")
  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        "http://169.254.169.254/latest/meta-data/",
        "https://169.254.169.254/latest/meta-data/",
        "http://localhost:8080/actuator",
        "https://evil.example.com/push",
        "https://fcm.googleapis.com.evil.example.com/push",
        "not-a-url"
      })
  void register_rejectsUnknownEndpoint(String endpoint) {
    assertThatThrownBy(() -> pushSubscriptionService.register(ME, endpoint, "key", "auth"))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(NotificationErrorCode.PUSH_ENDPOINT_NOT_ALLOWED);
  }

  /** {@code https} 가 아니면 안 된다. 알려진 호스트여도 평문으로는 보내지 않는다. */
  @DisplayName("알려진 호스트여도 https 가 아니면 등록되지 않는다.")
  @Test
  void register_requiresHttps() {
    assertThatThrownBy(
            () ->
                pushSubscriptionService.register(
                    ME, "http://fcm.googleapis.com/fcm/send/x", "key", "auth"))
        .isInstanceOf(BusinessException.class);
  }

  /**
   * 상한이 없으면 증폭 통로가 된다 (PR #157 리뷰).
   *
   * <p>구독 하나가 알림 하나마다 HTTP 요청 하나라, 수천 개를 등록해 두면 알림 한 건이 수천 번의 발송이 된다.
   */
  @DisplayName("기기 수가 상한을 넘으면 오래된 것부터 밀려난다.")
  @Test
  void register_evictsOldestBeyondLimit() {
    // given — 상한(10)까지 채운다
    for (int i = 0; i < 10; i++) {
      pushSubscriptionService.register(ME, "https://fcm.googleapis.com/fcm/send/d" + i, "k", "a");
    }

    // when
    pushSubscriptionService.register(ME, "https://fcm.googleapis.com/fcm/send/new", "k", "a");

    // then — 수는 그대로이고 가장 오래된 것이 빠진다
    assertThat(endpointsOf(ME))
        .hasSize(10)
        .doesNotContain("https://fcm.googleapis.com/fcm/send/d0")
        .contains("https://fcm.googleapis.com/fcm/send/new");
  }

  /** 재등록은 갱신이라 수가 늘지 않는다. 세면 재등록만 반복해도 멀쩡한 기기가 밀려난다. */
  @DisplayName("이미 있는 기기를 다시 등록해도 남이 밀려나지 않는다.")
  @Test
  void register_doesNotEvictOnRefresh() {
    // given
    for (int i = 0; i < 10; i++) {
      pushSubscriptionService.register(ME, "https://fcm.googleapis.com/fcm/send/d" + i, "k", "a");
    }

    // when — 가장 최근 기기가 구독을 갱신한다
    pushSubscriptionService.register(ME, "https://fcm.googleapis.com/fcm/send/d9", "k2", "a2");

    // then
    assertThat(endpointsOf(ME)).hasSize(10).contains("https://fcm.googleapis.com/fcm/send/d0");
  }

  private List<PushSubscription> subscriptionsOf(long userId) {
    return pushSubscriptionRepository.findByUserId(userId);
  }

  private List<String> endpointsOf(long userId) {
    return subscriptionsOf(userId).stream().map(PushSubscription::getEndpoint).toList();
  }
}
