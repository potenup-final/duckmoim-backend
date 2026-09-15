package com.duckmoim.notification.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.duckmoim.common.domain.NotificationKind;
import com.duckmoim.common.domain.NotificationTarget;
import com.duckmoim.notification.domain.NotificationDelivery;
import com.duckmoim.notification.domain.PushSubscription;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.Security;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import nl.martijndwars.webpush.Encoding;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import org.apache.http.HttpResponse;
import org.apache.http.HttpVersion;
import org.apache.http.message.BasicHttpResponse;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 한 사람의 기기들에 동시에 보낸다 (NT-12 · NT-13 · STAR-149).
 *
 * <p><b>한 건이 기기 수만큼 길어지면 안 된다.</b> 기기마다 보내고 기다리면 기기 셋이 30초가 되어 워커 리스를 넘기고, 다른 인스턴스가 같은 건을 다시 집어 같은
 * 푸시가 두 번 울린다 (NT-04).
 *
 * <pre>
 * 전  보내기(폰) → 기다림 10초 → 보내기(노트북) → 기다림 10초   = 20초
 * 후  보내기(폰) · 보내기(노트북) → 함께 기다림 10초           = 10초
 * </pre>
 *
 * <p><b>스프링을 띄우지 않는다.</b> {@code WebPushExpiryTest} 와 같은 이유다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("기기 동시 발송")
class WebPushConcurrencyTest {

  private static final long RECIPIENT_ID = 7L;

  /** 마감을 나눠 쓰는지 볼 때 쓰는 짧은 제한 시간. 운영 값(10초)을 실제로 기다리지 않는다. */
  private static final long SHORT_TIMEOUT_MILLIS = 400;

  @Mock private PushService pushService;
  @Mock private PushSubscriptionRepository pushSubscriptionRepository;

  private WebPushNotificationSender sender;

  /** 푸시 라이브러리가 BouncyCastle 위에서 돈다 — {@code WebPushExpiryTest} 의 같은 메서드 각주를 본다. */
  @BeforeAll
  static void registerBouncyCastle() {
    if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
      Security.addProvider(new BouncyCastleProvider());
    }
  }

  @BeforeEach
  void setUp() {
    sender =
        new WebPushNotificationSender(pushService, pushSubscriptionRepository, new ObjectMapper());
  }

  /**
   * <b>첫 응답을 기다리기 시작할 때 이미 모든 기기에 보냈는가</b>를 본다.
   *
   * <p>기다리는 시점마다 「지금까지 시작한 발송 수」를 적는다. 순서대로 보내던 때는 {@code 1, 2, 3} 이 적힌다.
   */
  @DisplayName("기기 여럿에 모두 보낸 뒤에 응답을 기다린다.")
  @Test
  void send_startsEveryDeviceBeforeWaiting() throws Exception {
    given(pushSubscriptionRepository.findByUserId(RECIPIENT_ID))
        .willReturn(List.of(aSubscription(1L), aSubscription(2L), aSubscription(3L)));
    AtomicInteger started = new AtomicInteger();
    List<Integer> startedWhenWaiting = new CopyOnWriteArrayList<>();
    given(pushService.sendAsync(any(Notification.class), any(Encoding.class)))
        .willAnswer(
            invocation -> {
              started.incrementAndGet();
              return respondingLater(started, startedWhenWaiting);
            });

    sender.send(delivery());

    assertThat(startedWhenWaiting).containsExactly(3, 3, 3);
  }

  /**
   * <b>마감은 기기마다가 아니라 한 건에 하나다.</b> 앞 기기가 제한 시간을 다 쓰면 뒤 기기는 기다리지 않고 바로 들여다본다.
   *
   * <p>기다리라고 넘긴 시간을 적는다. 기기마다 제한 시간을 새로 주던 때는 둘 다 10초가 적힌다.
   */
  @DisplayName("앞 기기가 제한 시간을 다 쓰면 뒤 기기는 남은 시간만 기다린다.")
  @Test
  void send_sharesOneDeadlineAcrossDevices() throws Exception {
    WebPushNotificationSender shortTimeout =
        new WebPushNotificationSender(
            pushService, pushSubscriptionRepository, new ObjectMapper(), SHORT_TIMEOUT_MILLIS);
    given(pushSubscriptionRepository.findByUserId(RECIPIENT_ID))
        .willReturn(List.of(aSubscription(1L), aSubscription(2L)));
    List<Long> waitedMillis = new CopyOnWriteArrayList<>();
    given(pushService.sendAsync(any(Notification.class), any(Encoding.class)))
        .willReturn(timingOut(waitedMillis, true))
        .willReturn(timingOut(waitedMillis, false));

    assertThatThrownBy(() -> shortTimeout.send(delivery()))
        .isInstanceOf(TransientPushException.class);

    assertThat(waitedMillis.get(0)).isGreaterThan(SHORT_TIMEOUT_MILLIS / 2);
    assertThat(waitedMillis.get(1)).isLessThan(SHORT_TIMEOUT_MILLIS / 2);
  }

  /** 기다리는 순간 「시작한 발송 수」를 적고 정상 응답을 준다. */
  private static CompletableFuture<HttpResponse> respondingLater(
      AtomicInteger started, List<Integer> startedWhenWaiting) {
    return new CompletableFuture<>() {
      @Override
      public HttpResponse get(long timeout, TimeUnit unit) {
        startedWhenWaiting.add(started.get());
        return new BasicHttpResponse(HttpVersion.HTTP_1_1, 201, null);
      }
    };
  }

  /**
   * 기다리라고 받은 시간을 적고 제한 시간 초과로 끝난다.
   *
   * <p>{@code consumeAll} 이면 받은 시간을 다 흘려보낸다 — 앞 기기가 마감을 다 쓴 상황을 만든다.
   */
  private static CompletableFuture<HttpResponse> timingOut(
      List<Long> waitedMillis, boolean consumeAll) {
    return new CompletableFuture<>() {
      @Override
      public HttpResponse get(long timeout, TimeUnit unit)
          throws java.util.concurrent.TimeoutException {
        long millis = unit.toMillis(timeout);
        waitedMillis.add(millis);
        if (consumeAll) {
          sleepQuietly(millis);
        }
        throw new java.util.concurrent.TimeoutException();
      }
    };
  }

  private static void sleepQuietly(long millis) {
    try {
      TimeUnit.MILLISECONDS.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /** 브라우저가 주는 모양의 구독 — 규격을 지킨 키여야 {@code Notification} 이 만들어진다. */
  private static PushSubscription aSubscription(long id) {
    PushSubscription subscription =
        PushSubscription.of(
            RECIPIENT_ID,
            "https://fcm.googleapis.com/fcm/send/device-" + id,
            "BKAkIEkIKw-HXWkEiz7ifp21YBpQ9pFzFy6_6j2TAwjssmzWop4jxAh6ge9ahG2WuvoaXN3W4nARgSLF0TLu8Yc",
            "cW5rb0hHSmZGWG0xVFhRMA");

    ReflectionTestUtils.setField(subscription, "id", id);
    ReflectionTestUtils.setField(subscription, "endpointHash", "hash-" + id);

    return subscription;
  }

  private static NotificationDelivery delivery() {
    return new NotificationDelivery(
        42L,
        RECIPIENT_ID,
        NotificationKind.POST_COMMENTED,
        NotificationTarget.ofComment(10L, 100L));
  }
}
