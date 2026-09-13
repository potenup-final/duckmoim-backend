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
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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

  /**
   * 한 기기의 발송을 기다릴 시간 (PR #157 리뷰).
   *
   * <p><b>없으면 스케줄러가 통째로 멈춘다.</b> 라이브러리의 {@code send} 는 {@code sendAsync(...).get()} 이고 그 {@code
   * get} 에 타임아웃이 없다 (5.1.2 바이트코드). 응답하지 않는 주소 하나면 그 호출이 영영 돌아오지 않는다.
   *
   * <pre>
   * taskScheduler 풀 = 2          (SchedulingConfig)
   * @Scheduled 메서드 = 일곱
   *      ↓
   * 물린 발송 둘  →  채팅 하트비트 · 이미지 정리 · 마감 배치 · 알림 만료까지 전부 멈춘다
   * </pre>
   *
   * <p>10초로 둔 것은 푸시 서비스가 정상일 때 수백 밀리초에 끝나기 때문이다. 넉넉하되 한 주기(10초)를 크게 넘기지 않는 값이다.
   */
  private static final long SEND_TIMEOUT_MILLIS = 10_000;

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
    deliverToEach(subscriptions, payload);
  }

  /**
   * 기기마다 따로 보내고 결과를 모은다 (PR #157 리뷰).
   *
   * <p><b>예외를 밖으로 흘리면 첫 실패가 나머지 기기를 끊는다.</b> 키가 깨진 구독 하나로 그 사람의 폰이 통째로 조용해지고, 아웃박스 행이 DLQ 로 가서
   * <b>어느 기기로도 못 받는다.</b>
   *
   * <p><b>하나라도 닿았으면 던지지 않는다.</b> 던지면 다음 주기가 <b>이미 받은 기기에 또 보낸다</b> — 한 건이 남기는 것은 재시도의 값어치보다 크다.
   *
   * <pre>
   * 성공 하나라도 있다      →  끝낸다. 못 받은 기기는 이번 건을 놓친다
   * 전부 되돌릴 수 없는 실패 →  끝낸다. 다시 해도 같다
   * 전부 일시 실패          →  던진다. 그때만 재시도가 값을 한다
   * </pre>
   *
   * <p><b>「되돌릴 수 없는 실패는 바로 DLQ」(ADR 0010)가 여기서 갈린다.</b> 그 결정은 채널이 하나이고 발송이 한 번일 때 쓴 것이라 <b>기기가 여럿인
   * 경우를 다루지 않는다.</b> 지금 구분은 이렇다.
   *
   * <pre>
   * 본문을 못 만든다        발송 전체의 실패다. 그대로 올라가 DLQ 로 간다
   * 이 기기가 못 받는다     그 기기만의 실패다. 인앱 알림은 이미 만들어져 있다
   * </pre>
   *
   * <p>후자를 DLQ 로 보내면 <b>사용자가 알림함에서 이미 본 알림이 「못 보낸 것」으로 장부에 남는다.</b>
   */
  private void deliverToEach(List<PushSubscription> subscriptions, String payload) {
    RuntimeException lastTransient = null;
    boolean anyDelivered = false;

    for (PushSubscription subscription : subscriptions) {
      try {
        sendToOne(subscription, payload);
        anyDelivered = true;

      } catch (PermanentPushException e) {
        // 그 기기만의 문제다. 나머지는 계속 보낸다.
        log.warn(
            "[WebPushNotificationSender.deliverToEach] 이 기기는 건너뛴다. subscriptionId={}",
            subscription.getId());

      } catch (RuntimeException e) {
        lastTransient = e;
      }
    }

    if (!anyDelivered && lastTransient != null) {
      throw lastTransient;
    }
  }

  /**
   * 기기 하나에 보낸다.
   *
   * <p><b>서명·암호화가 실패하면 되돌릴 수 없다.</b> 키 설정이 틀렸거나 구독이 준 키가 깨진 것이라 세 번 더 해도 같다.
   *
   * <p><b>만료면 그 구독을 지우고 성공으로 끝낸다</b> (NT-14).
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

      int status = statusOf(notification);

      if (isUnusable(status)) {
        forget(subscription, status);
        return;
      }

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
  /**
   * 보내고 응답 코드를 받는다. <b>정해진 시간을 넘기면 끊는다</b> (PR #157 리뷰).
   *
   * <p><b>{@code send} 를 쓰지 않고 {@code sendAsync} 를 쓰는 이유가 그것이다.</b> 전자는 타임아웃 없는 {@code get()} 이라
   * 응답하지 않는 주소에 영영 물린다.
   *
   * <p><b>물린 요청을 끊는다.</b> {@code cancel} 하지 않으면 타임아웃으로 빠져나온 뒤에도 그 요청이 커넥션을 쥔 채 남는다.
   */
  private int statusOf(Notification notification) throws Exception {
    Future<HttpResponse> pending = pushService.sendAsync(notification, Encoding.AES128GCM);

    try {
      return pending
          .get(SEND_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
          .getStatusLine()
          .getStatusCode();
    } catch (TimeoutException e) {
      pending.cancel(true);

      throw new TransientPushException("푸시 발송이 시간을 넘겼다.", e);
    }
  }

  /**
   * 그 구독을 다시 쓸 수 없다 (NT-14 · PR #157 리뷰).
   *
   * <pre>
   * 410 Gone        구독이 만료됐거나 사용자가 브라우저에서 알림을 껐다
   * 404 Not Found   푸시 서비스가 그 주소를 모른다
   * 400 Bad Request 그 구독이 준 키로는 본문을 만들 수 없다
   * </pre>
   *
   * <p>셋 다 <b>우리가 고칠 수 있는 실패가 아니고, 다시 보낼 대상도 아니다.</b>
   *
   * <p><b>{@code 403} 은 여기 없다.</b> 그것은 「구독을 만들 때 쓴 VAPID 키와 지금 서명한 키가 다르다」라서, 우리가 키를 잘못 바꾸면 <b>모든
   * 구독이 403 을 받는다.</b> 그때 지우면 전 사용자의 구독이 한 주기에 날아가고 되돌릴 수단이 없다 — 죽은 행이 남는 대가를 치르더라도 지우지 않는다.
   */
  private static boolean isUnusable(int status) {
    return status == 400 || status == 404 || status == 410;
  }

  /**
   * 만료된 구독을 지운다 (NT-14).
   *
   * <p><b>실패가 아니다.</b> 성공으로 끝내지 않으면 이미 전달된 인앱 알림이 시도 횟수를 까먹고, 세 번 겹치면 DLQ 로 간다 — 그 표는 「못 보낸 것」이라는
   * 뜻이므로 장부가 거짓이 된다 (ADR 0010).
   *
   * <p><b>기기가 여럿이면 나머지는 그대로 간다.</b> 하나가 만료됐다고 그 사람의 다른 기기를 건너뛰지 않는다.
   *
   * <p>지우다 실패해도 던지지 않는다. 다음 발송이 같은 답을 받아 다시 지운다 — 여기서 던지면 <b>멀쩡히 끝난 발송이 실패로 뒤집힌다.</b>
   */
  private void forget(PushSubscription subscription, int status) {
    try {
      pushSubscriptionRepository.deleteByEndpointHash(subscription.getEndpointHash());

      log.info(
          "[WebPushNotificationSender.forget] 못 쓰는 구독을 지웠다. subscriptionId={} status={}",
          subscription.getId(),
          status);

    } catch (RuntimeException e) {
      log.warn(
          "[WebPushNotificationSender.forget] 만료 구독 정리 실패. subscriptionId={} cause={}",
          subscription.getId(),
          e.getClass().getSimpleName());
    }
  }

  private RuntimeException classify(PushSubscription subscription, int status) {
    if (status == 429 || status >= 500) {
      return new TransientPushException("푸시 서비스가 거절했다. status=" + status, null);
    }

    if (status == 403) {
      // 구독을 만들 때 쓴 키와 지금 서명한 키가 다르다. 한 건의 문제가 아니라 설정의 문제일 수
      // 있어 크게 남긴다 — 키를 잘못 바꿨다면 전 사용자가 여기로 온다.
      log.error(
          "[WebPushNotificationSender.classify] VAPID 키가 구독과 맞지 않는다."
              + " 설정을 확인한다. subscriptionId={}",
          subscription.getId());

      return new PermanentPushException("푸시 서명이 구독과 맞지 않는다.");
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
   * <p><b>주소를 담지 않는다.</b> 한때 {@code url} 을 넣었는데, Service Worker 가 그 값을 쓰지 않고 <b>자기가 경로를 만들고 있었다</b>
   * (PR #157 · 프론트 확인). 아무도 안 읽는 값이 남아 있으면 틀려도 아무도 모른다.
   *
   * <p><b>그래서 이 서버는 프론트 라우트를 모른다.</b> 주소 모양이 바뀌어도 서버를 고치고 배포할 일이 없다 — 화면의 일은 화면이 정한다.
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
    payload.put("tag", tagOf(delivery.kind(), target));

    try {
      return objectMapper.writeValueAsString(payload);
    } catch (JsonProcessingException e) {
      // 값이 전부 Long·enum·String 이라 직렬화가 실패할 이유가 없다. 여기 오면 코드가 깨진
      // 것이고, 재시도로 풀릴 일이 아니다.
      throw new PermanentPushException("푸시 본문을 만들 수 없다.", e);
    }
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
