package com.duckmoim.notification.infra;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.duckmoim.common.domain.NotificationKind;
import com.duckmoim.common.domain.NotificationTarget;
import com.duckmoim.notification.domain.NotificationDelivery;
import com.duckmoim.notification.domain.PushSubscription;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.Security;
import java.util.List;
import java.util.concurrent.CompletableFuture;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 만료 구독 정리와 실패 분류 (NT-14 · ADR 0010).
 *
 * <p><b>만료는 실패가 아니다.</b> 실패로 세면 이미 전달된 인앱 알림이 시도 횟수를 까먹고, 세 번 겹치면 DLQ 로 간다 — 그 표는 「못 보낸 것」이라는 뜻이므로
 * 장부가 거짓이 된다.
 *
 * <p><b>스프링을 띄우지 않는다.</b> 보는 것이 「응답 코드마다 무엇을 하는가」라 컨텍스트가 필요 없다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("만료 구독 정리")
class WebPushExpiryTest {

  private static final long RECIPIENT_ID = 7L;
  private static final String PHONE_HASH = "phone-hash";

  @Mock private PushService pushService;
  @Mock private PushSubscriptionRepository pushSubscriptionRepository;

  private WebPushNotificationSender sender;

  /**
   * 푸시 라이브러리가 BouncyCastle 위에서 돈다.
   *
   * <p><b>운영에서는 {@code WebPushConfig} 가 등록한다.</b> 이 검사는 발송기를 직접 만들어 그 길을 지나지 않으므로 여기서 해 준다 — 빠뜨리면
   * {@code Notification} 을 만들다 {@code NoSuchProviderException} 이 나고, 그것이 발송 실패로 둔갑해 무엇을 재는지 알 수 없게
   * 된다.
   */
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

  /** <b>이 검사가 NT-14 의 검증 기준이다</b> — 만료 구독이 재시도 대상에서 빠진다. */
  @DisplayName("못 쓰는 구독은 지워지고 발송은 성공으로 끝난다.")
  @ParameterizedTest(name = "status {0}")
  @ValueSource(ints = {400, 404, 410})
  void send_forgetsGoneSubscription(int status) throws Exception {
    // given
    givenOneSubscription();
    givenResponse(status);

    // when & then — 예외가 나가면 워커가 이것을 실패로 센다
    assertThatCode(() -> sender.send(delivery())).doesNotThrowAnyException();

    then(pushSubscriptionRepository).should().deleteByEndpointHash(PHONE_HASH);
  }

  @DisplayName("정상 응답이면 구독을 지우지 않는다.")
  @Test
  void send_keepsLiveSubscription() throws Exception {
    // given
    givenOneSubscription();
    givenResponse(201);

    // when
    sender.send(delivery());

    // then
    then(pushSubscriptionRepository).should(never()).deleteByEndpointHash(anyString());
  }

  /** 저쪽 사정이라 재시도가 맞다. 지우면 다음 시도에 보낼 곳이 없다. */
  @DisplayName("잠시 미루라는 응답은 재시도로 남기고 구독도 지우지 않는다.")
  @ParameterizedTest(name = "status {0}")
  @ValueSource(ints = {429, 503})
  void send_retriesTransientFailure(int status) throws Exception {
    // given
    givenOneSubscription();
    givenResponse(status);

    // when & then
    assertThatThrownBy(() -> sender.send(delivery())).isInstanceOf(TransientPushException.class);

    then(pushSubscriptionRepository).should(never()).deleteByEndpointHash(anyString());
  }

  /**
   * <b>{@code 403} 에서는 지우지 않는다</b> (PR #157 리뷰).
   *
   * <p>그것은 「구독을 만들 때 쓴 VAPID 키와 지금 서명한 키가 다르다」라서, 우리가 키를 잘못 바꾸면 <b>모든 구독이 403 을 받는다.</b> 그때 지우면 전
   * 사용자의 구독이 한 주기에 날아가고 되돌릴 수단이 없다.
   */
  @DisplayName("키가 맞지 않으면 그 기기를 건너뛰되 구독은 남긴다.")
  @Test
  void send_keepsSubscriptionOnKeyMismatch() throws Exception {
    // given
    givenOneSubscription();
    givenResponse(403);

    // when & then — 기기 하나의 영구 실패는 발송 전체를 실패로 만들지 않는다
    assertThatCode(() -> sender.send(delivery())).doesNotThrowAnyException();

    then(pushSubscriptionRepository).should(never()).deleteByEndpointHash(anyString());
  }

  /**
   * <b>기기 하나의 실패가 나머지를 끊으면 안 된다</b> (PR #157 리뷰).
   *
   * <p>끊기면 키가 깨진 구독 하나로 그 사람의 다른 기기가 통째로 조용해지고, 아웃박스 행이 DLQ 로 가서 어느 기기로도 못 받는다.
   */
  @DisplayName("기기 하나가 못 쓰게 돼도 나머지에는 보낸다.")
  @Test
  void send_keepsGoingWhenOneDeviceFails() throws Exception {
    // given — 첫 기기는 400, 둘째는 정상
    given(pushSubscriptionRepository.findByUserId(RECIPIENT_ID))
        .willReturn(List.of(aSubscription(1L, PHONE_HASH), aSubscription(2L, "laptop-hash")));
    given(pushService.sendAsync(any(Notification.class), any(Encoding.class)))
        .willReturn(CompletableFuture.completedFuture(responseOf(400)))
        .willReturn(CompletableFuture.completedFuture(responseOf(201)));

    // when & then — 던지지 않는다
    assertThatCode(() -> sender.send(delivery())).doesNotThrowAnyException();

    then(pushService)
        .should(org.mockito.Mockito.times(2))
        .sendAsync(any(Notification.class), any(Encoding.class));
    then(pushSubscriptionRepository).should().deleteByEndpointHash(PHONE_HASH);
  }

  /**
   * <b>하나라도 닿았으면 던지지 않는다</b> (PR #157 리뷰).
   *
   * <p>던지면 다음 주기가 이미 받은 기기에 또 보낸다 — 한 건이 남기는 것이 재시도의 값어치보다 크다.
   */
  @DisplayName("한 기기만 일시 실패면 재시도하지 않는다.")
  @Test
  void send_doesNotRetryWhenSomeoneGotIt() throws Exception {
    // given — 첫 기기는 정상, 둘째는 일시 실패
    given(pushSubscriptionRepository.findByUserId(RECIPIENT_ID))
        .willReturn(List.of(aSubscription(1L, PHONE_HASH), aSubscription(2L, "laptop-hash")));
    given(pushService.sendAsync(any(Notification.class), any(Encoding.class)))
        .willReturn(CompletableFuture.completedFuture(responseOf(201)))
        .willReturn(CompletableFuture.completedFuture(responseOf(503)));

    // when & then
    assertThatCode(() -> sender.send(delivery())).doesNotThrowAnyException();
  }

  /** 아무에게도 못 갔고 다시 하면 될 수도 있을 때만 재시도가 값을 한다. */
  @DisplayName("모든 기기가 일시 실패면 재시도로 남긴다.")
  @Test
  void send_retriesWhenEveryDeviceFailedTransiently() throws Exception {
    // given
    given(pushSubscriptionRepository.findByUserId(RECIPIENT_ID))
        .willReturn(List.of(aSubscription(1L, PHONE_HASH), aSubscription(2L, "laptop-hash")));
    givenResponse(503);

    // when & then
    assertThatThrownBy(() -> sender.send(delivery())).isInstanceOf(TransientPushException.class);
  }

  /** 푸시를 켜지 않은 사용자에게도 인앱 알림은 남는다 — 실패가 아니다. */
  @DisplayName("구독이 하나도 없으면 아무 데도 보내지 않는다.")
  @Test
  void send_doesNothingWithoutSubscriptions() throws Exception {
    // given
    given(pushSubscriptionRepository.findByUserId(RECIPIENT_ID)).willReturn(List.of());

    // when
    sender.send(delivery());

    // then
    then(pushService).should(never()).sendAsync(any(Notification.class), any(Encoding.class));
  }

  private void givenOneSubscription() {
    given(pushSubscriptionRepository.findByUserId(RECIPIENT_ID))
        .willReturn(List.of(aSubscription()));
  }

  /**
   * <b>{@code send} 가 아니라 {@code sendAsync} 를 잡는다</b> (PR #157 리뷰).
   *
   * <p>전자는 타임아웃 없는 {@code get()} 이라 응답하지 않는 주소에 영영 물린다. 발송기가 그래서 후자를 쓴다.
   */
  private void givenResponse(int status) throws Exception {
    given(pushService.sendAsync(any(Notification.class), any(Encoding.class)))
        .willReturn(CompletableFuture.completedFuture(responseOf(status)));
  }

  private static HttpResponse responseOf(int status) {
    return new BasicHttpResponse(HttpVersion.HTTP_1_1, status, null);
  }

  /**
   * 브라우저가 주는 모양의 구독.
   *
   * <p>{@code p256dh} 는 65바이트 비압축 공개키, {@code auth} 는 16바이트라 규격을 지킨 값이어야 {@code Notification} 이
   * 만들어진다.
   */
  private static PushSubscription aSubscription() {
    return aSubscription(1L, PHONE_HASH);
  }

  private static PushSubscription aSubscription(long id, String endpointHash) {
    PushSubscription subscription =
        PushSubscription.of(
            RECIPIENT_ID,
            "https://fcm.googleapis.com/fcm/send/phone",
            "BKAkIEkIKw-HXWkEiz7ifp21YBpQ9pFzFy6_6j2TAwjssmzWop4jxAh6ge9ahG2WuvoaXN3W4nARgSLF0TLu8Yc",
            "cW5rb0hHSmZGWG0xVFhRMA");

    org.springframework.test.util.ReflectionTestUtils.setField(subscription, "id", id);
    org.springframework.test.util.ReflectionTestUtils.setField(
        subscription, "endpointHash", endpointHash);

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
