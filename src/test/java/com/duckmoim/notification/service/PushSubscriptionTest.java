package com.duckmoim.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

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

  private List<PushSubscription> subscriptionsOf(long userId) {
    return pushSubscriptionRepository.findByUserId(userId);
  }

  private List<String> endpointsOf(long userId) {
    return subscriptionsOf(userId).stream().map(PushSubscription::getEndpoint).toList();
  }
}
