package com.duckmoim.notification.infra;

import com.duckmoim.common.domain.NotificationKind;
import com.duckmoim.common.domain.NotificationTarget;
import com.duckmoim.notification.domain.NotificationDelivery;
import com.duckmoim.notification.domain.PushSubscription;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import nl.martijndwars.webpush.Encoding;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import org.apache.http.HttpResponse;

/**
 * 웹 푸시를 실제로 보낸다 (NT-13).
 *
 * <p><b>한 건이 기기 수만큼의 발송이다.</b> 한 유저가 여럿을 가질 수 있다 (NT-12).
 *
 * <p><b>구독이 하나도 없으면 아무 일도 하지 않는다.</b> 실패가 아니다 — 푸시를 켜지 않은 사용자에게도 인앱 알림은 남는다 ({@link
 * NotificationPushSender} 의 계약).
 *
 * <p><b>본문과 주소를 로그에 남기지 않는다.</b> 주소는 기기를 특정하는 개인정보이고 (처리방침 제1조) 본문에는 무엇에 대한 알림인지가 들어 있다.
 */
@Slf4j
public class WebPushNotificationSender implements NotificationPushSender {

  /**
   * 푸시 서비스가 알림을 붙들고 있을 시간(초).
   *
   * <p>기기가 꺼져 있으면 그동안 보관했다가 켜질 때 준다. 하루로 둔 것은 알림이 그보다 늦게 도착하면 이미 쓸모가 없기 때문이다 — 30일 만료(NT-11a)를 기다리는
   * 알림함과 갈리는 지점이고, 푸시는 「지금 불러오는 신호」다.
   */
  private static final int TTL_SECONDS = 86400;

  private final PushService pushService;
  private final PushSubscriptionRepository pushSubscriptionRepository;
  private final ObjectMapper objectMapper;

  public WebPushNotificationSender(
      PushService pushService,
      PushSubscriptionRepository pushSubscriptionRepository,
      ObjectMapper objectMapper) {

    this.pushService = pushService;
    this.pushSubscriptionRepository = pushSubscriptionRepository;
    this.objectMapper = objectMapper;
  }

  @Override
  public void send(NotificationDelivery delivery) {
    List<PushSubscription> subscriptions =
        pushSubscriptionRepository.findByUserId(delivery.recipientId());

    if (subscriptions.isEmpty()) {
      return;
    }

    String payload = encode(delivery);
    subscriptions.forEach(subscription -> sendToOne(subscription, payload));
  }

  /**
   * 기기 하나에 보낸다.
   *
   * <p><b>서명·암호화가 실패하면 되돌릴 수 없다.</b> 키 설정이 틀렸거나 구독이 준 키가 깨진 것이라 세 번 더 해도 같다.
   *
   * <p><b>만료 처리는 아직 없다.</b> NT-14 가 다음 커밋에서 {@link #classify} 에 붙는다.
   */
  private void sendToOne(PushSubscription subscription, String payload) {
    try {
      Notification notification =
          new Notification(
              subscription.getEndpoint(),
              subscription.getP256dh(),
              subscription.getAuth(),
              payload.getBytes(java.nio.charset.StandardCharsets.UTF_8),
              TTL_SECONDS);

      HttpResponse response = pushService.send(notification, Encoding.AES128GCM);
      int status = response.getStatusLine().getStatusCode();

      if (status >= 300) {
        throw classify(subscription, status);
      }
    } catch (InterruptedException e) {
      // 인터럽트는 삼키지 않는다. 배치 스레드가 내려가는 중이라는 뜻이라 그 신호를 되살려
      // 올려 보내고, 이 건은 다음 주기가 다시 집는다.
      Thread.currentThread().interrupt();
      throw new TransientPushException("푸시 발송이 중단됐다.", e);

    } catch (RuntimeException e) {
      throw e;

    } catch (Exception e) {
      // 라이브러리가 GeneralSecurityException · IOException · JoseException 을 검사 예외로
      // 던진다. 이음매에 throws 를 더하면 채널마다 다른 검사 예외가 계약에 쌓인다.
      throw new TransientPushException("푸시 발송이 실패했다.", e);
    }
  }

  /**
   * 응답 코드로 실패의 성격을 가른다 (ADR 0010).
   *
   * <p><b>4xx 를 재시도하지 않는다.</b> 잘못된 VAPID 서명이나 깨진 페이로드는 세 번 더 보내도 같은 답이 온다. 재시도로 태우면 NT-03 의 시도 횟수를
   * 헛되이 쓰고, 그동안 그 아웃박스 행은 「아직 못 보낸 것」으로 남는다.
   *
   * <p><b>{@code 429} 만 4xx 중 예외다.</b> 저쪽이 잠시 미루라는 뜻이라 재시도가 맞다.
   *
   * <p>{@code ERROR} 로 남기는 것은 재시도가 없어 <b>이 한 줄이 유일한 신호</b>이기 때문이다.
   */
  private RuntimeException classify(PushSubscription subscription, int status) {
    if (status == 429 || status >= 500) {
      return new TransientPushException("푸시 서비스가 거절했다. status=" + status, null);
    }

    // 주소를 남기지 않는다. 어느 구독이었는지는 번호로 찾는다.
    log.error(
        "[WebPushNotificationSender.classify] 되돌릴 수 없는 푸시 실패. subscriptionId={} status={}",
        subscription.getId(),
        status);

    return new PermanentPushException("푸시가 거절됐다. status=" + status);
  }

  /**
   * 보낼 본문을 만든다 (NT-13).
   *
   * <p><b>문구가 없다.</b> 알림함 항목과 같은 모양을 보내고 Service Worker 가 조립한다 — 서버가 문구를 쥐면 문안 한 줄 고치는 데 배포가 필요해진다
   * (API-설계.md 「2-10. 알림 (Notification) · 2차」).
   *
   * <p><b>{@code url} 이 이 서버가 프론트 라우트를 아는 유일한 자리다.</b> 클릭했을 때 갈 곳이 필요한데 그 경로는 프론트가 정한다. 라우트가 바뀌면
   * 여기도 바뀌어야 하므로, 넓히지 않고 이 파일 안에만 둔다.
   */
  private String encode(NotificationDelivery delivery) {
    NotificationTarget target = delivery.target();

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("notificationId", delivery.outboxId());
    payload.put("kind", delivery.kind());
    payload.put("postId", target.postId());
    payload.put("commentId", target.commentId());
    payload.put("roomId", target.roomId());
    payload.put("messageId", target.messageId());
    payload.put("url", urlOf(delivery.kind(), target));
    payload.put("tag", tagOf(delivery.kind(), target));

    try {
      return objectMapper.writeValueAsString(payload);
    } catch (JsonProcessingException e) {
      // 값이 전부 Long·enum·String 이라 직렬화가 실패할 이유가 없다. 여기 오면 코드가 깨진
      // 것이고, 재시도로 풀릴 일이 아니다.
      throw new PermanentPushException("푸시 본문을 만들 수 없다.", e);
    }
  }

  /** 알림을 눌렀을 때 갈 곳. 프론트 라우트다. */
  private static String urlOf(NotificationKind kind, NotificationTarget target) {
    return kind == NotificationKind.ROOM_MESSAGED
        ? "/chat-rooms/" + target.roomId()
        : "/posts/" + target.postId() + "#comment-" + target.commentId();
  }

  /**
   * 같은 대상의 알림이 겹쳐 쌓이지 않게 하는 값.
   *
   * <p>대화가 활발할 때 알림이 기기에 스무 개씩 쌓이는 것을 막는다 — 같은 태그는 마지막 것만 남는다.
   */
  private static String tagOf(NotificationKind kind, NotificationTarget target) {
    return kind == NotificationKind.ROOM_MESSAGED
        ? "room-" + target.roomId()
        : "post-" + target.postId();
  }
}
