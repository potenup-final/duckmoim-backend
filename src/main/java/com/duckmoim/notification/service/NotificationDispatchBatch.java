package com.duckmoim.notification.service;

import com.duckmoim.notification.domain.NotificationDelivery;
import com.duckmoim.notification.infra.NotificationPushSender;
import com.duckmoim.notification.infra.PermanentPushException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 아웃박스에 쌓인 알림을 주기적으로 보낸다 (NT-02 · NT-03).
 *
 * <p><b>엔드포인트가 없다.</b> 발송은 사용자 요청이 아니라 서버 스케줄러의 일이다 — 마감 배치(PO-14)와 같은 자리다.
 *
 * <p><b>{@code @Scheduled} 를 service 에 둔다.</b> 네 레이어 밖에 패키지를 새로 만들면 {@code LAYER_DEPENDENCY} 가 그
 * 클래스의 service 참조를 아예 검사하지 않는다 ({@code consideringOnlyDependenciesInLayers}) — 마감 배치 javadoc 이 적어 둔
 * 근거를 그대로 따른다.
 *
 * <p><b>여기는 트랜잭션을 열지 않는다.</b> 건마다 트랜잭션이 따로여서 (실패 기록이 롤백에 함께 지워지면 안 된다) 반복만 진다. 트랜잭션 경계는 {@link
 * NotificationDispatchService} 다.
 *
 * <p><b>선점은 집는 쪽이 한다</b> (NT-04). {@link NotificationDispatchService#claimSendableIds} 가 잠그고 읽은 뒤
 * 리스를 적어서, 두 워커가 같은 건을 집지 않는다.
 *
 * <p><b>그래도 겹칠 자리가 남는다.</b> 리스가 만료된 뒤에 앞선 워커가 살아 돌아오는 경우다. 뒤에 집은 워커는 두 갈래로 끝난다 — 앞선 워커가 이미 커밋했으면
 * {@link NotificationDispatchService#dispatch} 가 「보낼 것이 아니다」로 넘어가고, 커밋이 그 사이에 끼면 유니크 제약에 걸려 롤백된 뒤
 * 실패 기록도 「남이 보냈다」로 넘어간다. 둘 다 예외가 아니다. 예외로 다루면 주기가 끝나고, 실패로 세면 전달된 알림이 DLQ 로 간다.
 */
@Service
@Slf4j
public class NotificationDispatchBatch {

  /**
   * 한 주기가 돌릴 최대 청크 수.
   *
   * <p>마감 배치와 같은 이유로 둔다 — 조회 조건과 처리 결과가 어긋나면 같은 건을 무한히 다시 집는데, 그때 상한이 없으면 배치 스레드가 영구히 물린다. 여기서는
   * 「보냈다고 적히지 않는 건」이 그 경우다.
   */
  private static final int MAX_CHUNKS = 100;

  private final NotificationDispatchService notificationDispatchService;
  private final NotificationPushSender pushSender;
  private final Clock clock;

  /** 한 번 조회로 집을 최대 건수. 프로퍼티인 것은 이 반복이 몇 건짜리 테스트로 증명되어야 하기 때문이다. */
  private final int chunk;

  public NotificationDispatchBatch(
      NotificationDispatchService notificationDispatchService,
      NotificationPushSender pushSender,
      Clock clock,
      @Value("${duckmoim.notification.worker.chunk}") int chunk) {

    this.notificationDispatchService = notificationDispatchService;
    this.pushSender = pushSender;
    this.clock = clock;
    this.chunk = chunk;
  }

  /**
   * 보낼 것이 없어질 때까지 청크를 돌린다.
   *
   * <p><b>예외를 밖으로 던지지 않는다.</b> 스케줄러에서 예외가 올라가면 다음 실행이 오는지가 설정에 달린다. 여기서 잡아 남기면 다음 주기가 곧 재시도가 되고, 건별
   * 재시도는 그 아래에서 시도 횟수로 따로 센다 (NT-03).
   *
   * <p><b>0건일 때는 남기지 않는다.</b> 10초 주기라 「0건 보냄」을 남기면 하루에 8640 줄이 쌓여 실제로 보낸 날을 찾을 수 없다.
   */
  @Scheduled(cron = "${duckmoim.notification.worker.cron}")
  public void dispatchPendingNotifications() {
    try {
      int sent = dispatchUntilDrained(nowInUtc());

      if (sent > 0) {
        log.info(
            "[NotificationDispatchBatch.dispatchPendingNotifications] Notifications sent. count={}",
            sent);
      }
    } catch (Exception exception) {
      log.error(
          "[NotificationDispatchBatch.dispatchPendingNotifications] Failed to dispatch.",
          exception);
    }
  }

  private int dispatchUntilDrained(LocalDateTime nowInUtc) {
    int sent = 0;

    for (int chunks = 0; chunks < MAX_CHUNKS; chunks++) {
      List<Long> ids = notificationDispatchService.claimSendableIds(nowInUtc, chunk);

      for (Long outboxId : ids) {
        if (dispatchOne(outboxId, nowInUtc)) {
          sent++;
        }
      }

      if (ids.size() < chunk) {
        return sent;
      }
    }

    log.warn("[NotificationDispatchBatch.dispatchUntilDrained] Chunk limit reached. sent={}", sent);

    return sent;
  }

  /**
   * 한 건을 보낸다. 실패는 여기서 멈추고 다음 건으로 넘어간다.
   *
   * <p><b>단계가 셋이다</b> (ADR 0010). 인앱을 만들어 커밋하고(T1), 푸시를 트랜잭션 밖에서 보내고(T2), 결과를 적는다(T3). 한 트랜잭션에 담으면
   * 푸시 실패가 인앱 알림을 롤백시킨다.
   *
   * <p>T1 이 빈 값을 주면 그 행은 이미 끝났거나 사라진 것이라 <b>T2 · T3 을 건너뛴다.</b>
   *
   * <p><b>실패 기록이 발송과 다른 트랜잭션이다.</b> 발송이 롤백된 뒤에 불러야 시도 횟수가 남는다.
   *
   * <p>실패 로그에 예외를 함께 남기는 것은 무엇이 실패했는지가 DLQ 에 남지 않기 때문이다 — 다 쓴 건만 옮겨지고 (NT-03), 중간 실패의 원인은 로그가 유일한
   * 기록이다.
   */
  private boolean dispatchOne(Long outboxId, LocalDateTime nowInUtc) {
    try {
      return notificationDispatchService
          .deliverInApp(outboxId)
          .filter(this::pushThenMark)
          .isPresent();

    } catch (PermanentPushException permanent) {
      abandon(outboxId, nowInUtc, permanent);
      return false;

    } catch (Exception exception) {
      recordFailure(outboxId, nowInUtc, exception);

      return false;
    }
  }

  /**
   * 실패를 적는다. <b>이 호출이 실패해도 주기를 끝내지 않는다.</b>
   *
   * <p>catch 블록 안에서 부르는 것이라 여기서 나간 예외는 {@code dispatchOne} 과 반복문을 뚫고 주기 전체를 끝낸다 — 한 건의 실패가 남은 건을 다음
   * 주기까지 미루게 된다. 기록을 못 남기는 것은 다음 주기가 다시 시도하면 되는 일이지만, 주기가 끝나는 것은 그렇지 않다.
   */
  private void recordFailure(Long outboxId, LocalDateTime nowInUtc, Exception cause) {
    try {
      if (notificationDispatchService.recordFailure(outboxId, nowInUtc)) {
        log.error(
            "[NotificationDispatchBatch.recordFailure] Notification gave up. outboxId={}",
            outboxId,
            cause);
      } else {
        log.warn(
            "[NotificationDispatchBatch.recordFailure] Notification failed. outboxId={},"
                + " exception={}",
            outboxId,
            cause.getClass().getSimpleName());
      }
    } catch (Exception exception) {
      log.error(
          "[NotificationDispatchBatch.recordFailure] Failed to record failure. outboxId={}",
          outboxId,
          exception);
    }
  }

  /**
   * UTC 기준 현재 시각.
   *
   * <p><b>{@code LocalDateTime.now(clock)} 이 아니다.</b> {@code ClockConfig} 의 시계가 {@code Asia/Seoul}
   * 이라 그것을 넣으면 UTC 로 저장된 {@code next_attempt_at} 과 아홉 시간 어긋나서 <b>기다리라고 미뤄 둔 건을 바로 다시 집는다.</b> 마감
   * 배치와 {@code AuditLogRecorder} 가 같은 자리에서 같은 변환을 쓴다.
   */
  private LocalDateTime nowInUtc() {
    return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
  }

  /**
   * T2 · T3 — 푸시를 보내고 결과를 적는다 (ADR 0010).
   *
   * <p><b>푸시가 트랜잭션 밖에서 돈다.</b> 여기서 예외가 나면 T1 이 만든 인앱 알림은 이미 커밋돼 남아 있고, 아웃박스 행만 {@code PENDING} 으로
   * 남아 다음 주기에 <b>인앱을 건너뛰고 푸시만</b> 재시도한다.
   */
  private boolean pushThenMark(NotificationDelivery delivery) {
    pushSender.send(delivery);

    return notificationDispatchService.markDelivered(delivery.outboxId());
  }

  /**
   * 되돌릴 수 없는 실패를 바로 DLQ 로 보낸다 (ADR 0010).
   *
   * <p><b>예외 타입으로 가른다.</b> 던지는 쪽이 자기 실패의 성격을 안다 — 여기서 예외를 뜯어 판정하게 하면 그 판정을 빠뜨릴 수 있고, 빠뜨리면 영영 실패할 건이
   * NT-03 의 세 번을 소진한다.
   *
   * <p>로그를 {@code ERROR} 로 남긴다. 재시도가 없어 <b>이 한 줄이 유일한 신호</b>다.
   */
  private void abandon(Long outboxId, LocalDateTime nowInUtc, Exception cause) {
    try {
      if (notificationDispatchService.abandon(outboxId, nowInUtc)) {
        log.error(
            "[NotificationDispatchBatch.abandon] Moved to DLQ without retry. outboxId={}",
            outboxId,
            cause);
      }
    } catch (Exception failed) {
      log.error(
          "[NotificationDispatchBatch.abandon] Failed to abandon. outboxId={}", outboxId, failed);
    }
  }
}
