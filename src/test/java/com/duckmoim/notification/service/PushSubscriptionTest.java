package com.duckmoim.notification.service;

import static com.duckmoim.identity.UserFixture.aUser;
import static org.assertj.core.api.Assertions.assertThat;

import com.duckmoim.identity.domain.UserWithdrawn;
import com.duckmoim.identity.service.UserService;
import com.duckmoim.notification.domain.PushSubscription;
import com.duckmoim.notification.infra.PushSubscriptionRepository;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
    pushSubscriptionService.register(OTHER, "https://x/other", "key", "auth");

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

  private List<PushSubscription> subscriptionsOf(long userId) {
    return pushSubscriptionRepository.findByUserId(userId);
  }

  private List<String> endpointsOf(long userId) {
    return subscriptionsOf(userId).stream().map(PushSubscription::getEndpoint).toList();
  }
}
