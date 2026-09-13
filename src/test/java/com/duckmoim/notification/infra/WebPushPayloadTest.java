package com.duckmoim.notification.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.duckmoim.common.domain.NotificationKind;
import com.duckmoim.common.domain.NotificationTarget;
import com.duckmoim.notification.domain.NotificationDelivery;
import com.duckmoim.notification.domain.PushSubscription;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.Security;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import nl.martijndwars.webpush.Encoding;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import org.apache.http.HttpVersion;
import org.apache.http.message.BasicHttpResponse;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 푸시 본문의 모양 (NT-13).
 *
 * <p><b>이것이 프론트와의 계약이다.</b> Service Worker 가 이 값으로 알림 문구를 조립하므로, 키가 하나 사라지면 화면에서 조용히 빈칸이 된다 — 서버 쪽
 * 검사는 전부 통과한 채로.
 *
 * <p><b>주소를 담지 않는 것이 이 검사의 요지다.</b> 한때 {@code url} 을 넣었는데 Service Worker 가 그것을 쓰지 않고 자기가 경로를 만들고
 * 있었다 (PR #157 · 프론트 확인). 다시 넣으면 아무도 안 읽는 값이 늘고, <b>서버가 프론트 라우트를 알게 된다.</b>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("푸시 본문")
class WebPushPayloadTest {

  private static final long RECIPIENT_ID = 7L;

  @Mock private PushService pushService;
  @Mock private PushSubscriptionRepository pushSubscriptionRepository;

  @Captor private ArgumentCaptor<Notification> sent;

  private WebPushNotificationSender sender;

  @BeforeAll
  static void registerBouncyCastle() {
    if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
      Security.addProvider(new BouncyCastleProvider());
    }
  }

  @BeforeEach
  void setUp() throws Exception {
    sender =
        new WebPushNotificationSender(pushService, pushSubscriptionRepository, new ObjectMapper());

    given(pushSubscriptionRepository.findByUserId(RECIPIENT_ID))
        .willReturn(List.of(aSubscription()));
    given(pushService.sendAsync(any(Notification.class), any(Encoding.class)))
        .willReturn(
            CompletableFuture.completedFuture(
                new BasicHttpResponse(HttpVersion.HTTP_1_1, 201, null)));
  }

  @DisplayName("댓글 알림은 종류와 모집글·댓글을 싣는다.")
  @Test
  void encode_carriesCommentTarget() throws Exception {
    // when
    sender.send(
        new NotificationDelivery(
            42L,
            RECIPIENT_ID,
            NotificationKind.POST_COMMENTED,
            NotificationTarget.ofComment(10L, 100L)));

    // then
    JsonNode payload = capturedPayload();
    assertThat(payload.get("notificationId").asLong()).isEqualTo(42L);
    assertThat(payload.get("kind").asText()).isEqualTo("POST_COMMENTED");
    assertThat(payload.get("postId").asLong()).isEqualTo(10L);
    assertThat(payload.get("commentId").asLong()).isEqualTo(100L);
    assertThat(payload.get("roomId").isNull()).isTrue();
    assertThat(payload.get("messageId").isNull()).isTrue();
  }

  /** 같은 대상의 알림이 기기에 겹쳐 쌓이지 않게 하는 값이다. 문구가 아니라 동작이라 서버가 준다. */
  @DisplayName("채팅 알림은 방을 가리키고 태그가 방 단위다.")
  @Test
  void encode_carriesRoomTarget() throws Exception {
    // when
    sender.send(
        new NotificationDelivery(
            43L,
            RECIPIENT_ID,
            NotificationKind.ROOM_MESSAGED,
            NotificationTarget.ofRoomMessage(3L, 777L)));

    // then
    JsonNode payload = capturedPayload();
    assertThat(payload.get("roomId").asLong()).isEqualTo(3L);
    assertThat(payload.get("messageId").asLong()).isEqualTo(777L);
    assertThat(payload.get("postId").isNull()).isTrue();
    assertThat(payload.get("tag").asText()).isEqualTo("room-3");
  }

  /**
   * <b>주소를 담지 않는다</b> (PR #157 · 프론트 확인).
   *
   * <p>Service Worker 가 경로를 직접 만든다. 서버가 또 만들어 보내면 아무도 안 읽는 값이 남고, 그 값이 틀려도 드러나지 않는다. 무엇보다 <b>프론트
   * 라우트가 바뀔 때 서버를 고치고 배포해야</b> 한다.
   */
  @DisplayName("본문에 주소를 담지 않는다.")
  @Test
  void encode_hasNoUrl() throws Exception {
    // when
    sender.send(
        new NotificationDelivery(
            42L,
            RECIPIENT_ID,
            NotificationKind.POST_COMMENTED,
            NotificationTarget.ofComment(10L, 100L)));

    // then
    assertThat(capturedPayload().has("url")).isFalse();
  }

  private JsonNode capturedPayload() throws Exception {
    then(pushService).should().sendAsync(sent.capture(), any(Encoding.class));

    return new ObjectMapper()
        .readTree(new String(sent.getValue().getPayload(), StandardCharsets.UTF_8));
  }

  private static PushSubscription aSubscription() {
    return PushSubscription.of(
        RECIPIENT_ID,
        "https://fcm.googleapis.com/fcm/send/phone",
        "BKAkIEkIKw-HXWkEiz7ifp21YBpQ9pFzFy6_6j2TAwjssmzWop4jxAh6ge9ahG2WuvoaXN3W4nARgSLF0TLu8Yc",
        "cW5rb0hHSmZGWG0xVFhRMA");
  }
}
