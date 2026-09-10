package com.duckmoim.notification.service;

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
 * <p><b>선점이 없다.</b> 인스턴스가 둘이면 두 워커가 같은 건을 집을 수 있다 (NT-04 가 넣는다). 그때 알림함이 두 벌이 되는 것은 {@code
 * notification} 표의 {@code outbox_id} 유니크 제약이 막고, 뒤에 집은 쪽은 「이미 있다」로 읽어 상태만 바꾼다. <b>남는 낭비는 헛일이지 중복
 * 발송이 아니다.</b>
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
  private final Clock clock;

  /** 한 번 조회로 집을 최대 건수. 프로퍼티인 것은 이 반복이 몇 건짜리 테스트로 증명되어야 하기 때문이다. */
  private final int chunk;

  public NotificationDispatchBatch(
      NotificationDispatchService notificationDispatchService,
      Clock clock,
      @Value("${duckmoim.notification.worker.chunk}") int chunk) {

    this.notificationDispatchService = notificationDispatchService;
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
      List<Long> ids = notificationDispatchService.findSendableIds(nowInUtc, chunk);

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
   * <p><b>실패 기록이 발송과 다른 트랜잭션이다.</b> 발송이 롤백된 뒤에 불러야 시도 횟수가 남는다.
   *
   * <p>실패 로그에 예외를 함께 남기는 것은 무엇이 실패했는지가 DLQ 에 남지 않기 때문이다 — 다 쓴 건만 옮겨지고 (NT-03), 중간 실패의 원인은 로그가 유일한
   * 기록이다.
   */
  private boolean dispatchOne(Long outboxId, LocalDateTime nowInUtc) {
    try {
      return notificationDispatchService.dispatch(outboxId);

    } catch (Exception exception) {
      boolean exhausted = notificationDispatchService.recordFailure(outboxId, nowInUtc);

      if (exhausted) {
        log.error(
            "[NotificationDispatchBatch.dispatchOne] Notification gave up. outboxId={}",
            outboxId,
            exception);
      } else {
        log.warn(
            "[NotificationDispatchBatch.dispatchOne] Notification failed. outboxId={}, exception={}",
            outboxId,
            exception.getClass().getSimpleName());
      }

      return false;
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
}
