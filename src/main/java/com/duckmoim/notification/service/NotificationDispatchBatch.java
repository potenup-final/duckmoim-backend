package com.duckmoim.notification.service;

import com.duckmoim.notification.domain.NotificationDelivery;
import com.duckmoim.notification.infra.NotificationPushSender;
import com.duckmoim.notification.infra.PermanentPushException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
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
 *
 * <h2>주기는 알림함만 만들고 푸시는 넘긴다 (STAR-149)</h2>
 *
 * <p><b>주기가 푸시를 기다리지 않는다.</b> 한 줄로 처리하던 때는 푸시 서비스가 느린 날 뒤에 선 사람의 알림함이 앞사람 푸시를 기다렸고 (QA-FAIL-04),
 * 주기가 길어지는 동안 다음 주기도 시작하지 못했다.
 *
 * <pre>
 * 청크마다  집기(리스) → 청크 전체 알림함 INSERT(T1) → 한 건씩 일꾼 자리가 나면 넘김
 *                                                          │
 *                                                          └─ 일꾼: 푸시(T2) → 결과 기록(T3)
 * </pre>
 *
 * <p><b>알림함을 청크 전체에 먼저 넣는다.</b> 넘기는 쪽은 자리를 기다릴 수 있어서, 한 건씩 번갈아 하면 뒤쪽 알림함이 앞쪽 푸시 자리를 기다리게 된다.
 *
 * <p><b>자리가 날 때까지 기다리되 넘기기 마감까지만이다</b> (STAR-149 리뷰). 곧바로 거절하던 판본은 채팅 메시지 하나로 알림이 수십 건 생기면 일꾼 수를 넘는
 * 건을 모두 리스(30초)에 묶어, <b>푸시 서비스가 정상인 날에도</b> 30~40초 늦게 보냈다. 마감은 청크를 집는 순간부터 잰다 — 리스도 그때부터 흐르기 때문이다.
 *
 * <p><b>마감까지 자리가 안 나 넘기지 못한 건은 리스를 그대로 둔다.</b> 알림함은 이미 만들어졌고, 리스가 풀린 뒤 다시 집히면 T1 의 멱등 검사로 알림함을 건너뛰고
 * 푸시만 넘긴다 (ADR 0010 이 적어 둔 「{@code PENDING} 인데 알림함에 행이 있다」 계약 그대로다). 리스를 바로 풀면 같은 주기의 다음 청크가 그 건을
 * 다시 집어 드레인이 헛돈다 — 조회 조건이 「주기 시작 시각 이전」이기 때문이다.
 *
 * <p><b>넘긴 건은 리스 안에 끝난다.</b> 넘기기 마감(15초)과 발송 제한 시간(10초)을 더해도 리스(30초)보다 짧다 — 그 관계를 {@code
 * NotificationLeaseBudgetTest} 가 지킨다. 넘긴 건의 리스가 발송 중에 만료되면 다른 인스턴스가 다시 집어 같은 푸시가 두 번 울린다 (NT-04).
 *
 * <p><b>대가는 스케줄러 스레드다.</b> 푸시 서비스가 느린 날에는 한 주기에 넘기기 마감만큼 이 스레드를 쥔다 — 한 청크가 마감에 걸리면 일꾼이 꽉 찬 것이라, 그
 * 주기의 나머지 청크는 기다리지 않고 알림함만 만든다. 평소에는 일꾼 자리가 수백 ms 마다 나서 거의 기다리지 않는다.
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
  private final NotificationPushExecutor pushExecutor;
  private final Clock clock;

  /** 한 번 조회로 집을 최대 건수. 프로퍼티인 것은 이 반복이 몇 건짜리 테스트로 증명되어야 하기 때문이다. */
  private final int chunk;

  /** 청크를 집은 뒤 푸시를 일꾼에 넘기려고 기다릴 수 있는 최대 시간. 발송 제한 시간과 더해 리스 안에 들어와야 한다. */
  private final Duration handoffWait;

  public NotificationDispatchBatch(
      NotificationDispatchService notificationDispatchService,
      NotificationPushSender pushSender,
      NotificationPushExecutor pushExecutor,
      Clock clock,
      @Value("${duckmoim.notification.worker.chunk}") int chunk,
      @Value("${duckmoim.notification.worker.handoff-wait}") Duration handoffWait) {

    this.notificationDispatchService = notificationDispatchService;
    this.pushSender = pushSender;
    this.pushExecutor = pushExecutor;
    this.clock = clock;
    this.chunk = chunk;
    this.handoffWait = handoffWait;
  }

  /**
   * 보낼 것이 없어질 때까지 청크를 돌린다.
   *
   * <p><b>예외를 밖으로 던지지 않는다.</b> 스케줄러에서 예외가 올라가면 다음 실행이 오는지가 설정에 달린다. 여기서 잡아 남기면 다음 주기가 곧 재시도가 되고, 건별
   * 재시도는 그 아래에서 시도 횟수로 따로 센다 (NT-03).
   *
   * <p><b>0건일 때는 남기지 않는다.</b> 10초 주기라 「0건 보냄」을 남기면 하루에 8640 줄이 쌓여 실제로 보낸 날을 찾을 수 없다.
   *
   * <p><b>푸시는 이 메서드가 끝난 뒤에도 돌고 있다.</b> 여기서 세는 것은 알림함에 넣은 건과 일꾼에 넘기지 못한 건이고, 푸시 결과는 일꾼이 건마다 남긴다.
   */
  @Scheduled(cron = "${duckmoim.notification.worker.cron}")
  public void dispatchPendingNotifications() {
    try {
      Drained drained = dispatchUntilDrained(nowInUtc());

      if (drained.delivered() > 0) {
        log.info(
            "[NotificationDispatchBatch.dispatchPendingNotifications] Notifications delivered."
                + " count={}, pushDeferred={}",
            drained.delivered(),
            drained.pushDeferred());
      }
    } catch (Exception exception) {
      log.error(
          "[NotificationDispatchBatch.dispatchPendingNotifications] Failed to dispatch.",
          exception);
    }
  }

  private Drained dispatchUntilDrained(LocalDateTime nowInUtc) {
    int delivered = 0;
    int pushDeferred = 0;

    for (int chunks = 0; chunks < MAX_CHUNKS; chunks++) {
      // 리스가 흐르기 시작하는 순간부터 잰다. 벽시계가 아니라 경과 시간이라 Clock 을 쓰지 않는다.
      //
      // 앞 청크에서 이미 마감에 걸렸으면 일꾼이 꽉 찬 것이라 기다리지 않는다. 기다리면 적체가
      // 여러 청크인 느린 날 청크마다 마감만큼 밀려, 뒤 청크의 알림함이 늦어진다.
      Duration wait = pushDeferred > 0 ? Duration.ZERO : handoffWait;
      long handoffDeadline = System.nanoTime() + wait.toNanos();
      List<Long> ids = notificationDispatchService.claimSendableIds(nowInUtc, chunk);

      List<NotificationDelivery> deliveries =
          ids.stream()
              .map(outboxId -> deliverInApp(outboxId, nowInUtc))
              .flatMap(Optional::stream)
              .toList();
      delivered += deliveries.size();

      for (NotificationDelivery delivery : deliveries) {
        Duration left = Duration.ofNanos(handoffDeadline - System.nanoTime());

        if (!pushExecutor.submitWithin(() -> pushAndRecord(delivery), left)) {
          pushDeferred++;
        }
      }

      if (ids.size() < chunk) {
        return new Drained(delivered, pushDeferred);
      }
    }

    log.warn(
        "[NotificationDispatchBatch.dispatchUntilDrained] Chunk limit reached. delivered={}",
        delivered);

    return new Drained(delivered, pushDeferred);
  }

  /**
   * 한 주기의 결과.
   *
   * @param delivered 알림함에 넣었거나 이미 있어 푸시로 넘어간 건수
   * @param pushDeferred 넘기기 마감까지 일꾼 자리가 안 나 푸시를 넘기지 못한 건수. 리스가 풀린 뒤 다시 집힌다
   */
  private record Drained(int delivered, int pushDeferred) {}

  /**
   * T1 — 알림함에 넣는다. 실패는 여기서 멈추고 다음 건으로 넘어간다.
   *
   * <p><b>단계가 셋이다</b> (ADR 0010). 인앱을 만들어 커밋하고(T1), 푸시를 트랜잭션 밖에서 보내고(T2), 결과를 적는다(T3). 한 트랜잭션에 담으면
   * 푸시 실패가 인앱 알림을 롤백시킨다. T2 · T3 은 {@link #pushAndRecord} 가 일꾼 스레드에서 한다.
   *
   * <p>빈 값이면 그 행은 이미 끝났거나 사라진 것이라 <b>푸시를 넘기지 않는다.</b>
   *
   * <p><b>실패 기록이 발송과 다른 트랜잭션이다.</b> 발송이 롤백된 뒤에 불러야 시도 횟수가 남는다.
   *
   * <p>실패 로그에 예외를 함께 남기는 것은 무엇이 실패했는지가 DLQ 에 남지 않기 때문이다 — 다 쓴 건만 옮겨지고 (NT-03), 중간 실패의 원인은 로그가 유일한
   * 기록이다.
   */
  private Optional<NotificationDelivery> deliverInApp(Long outboxId, LocalDateTime nowInUtc) {
    try {
      return notificationDispatchService.deliverInApp(outboxId);

    } catch (Exception exception) {
      recordFailure(outboxId, nowInUtc, exception);

      return Optional.empty();
    }
  }

  /**
   * T2 · T3 — 푸시를 보내고 결과를 적는다 (ADR 0010). <b>일꾼 스레드에서 돈다.</b>
   *
   * <p><b>푸시가 트랜잭션 밖에서 돈다.</b> 여기서 예외가 나면 T1 이 만든 인앱 알림은 이미 커밋돼 남아 있고, 아웃박스 행만 {@code PENDING} 으로
   * 남아 다음 시도에 <b>인앱을 건너뛰고 푸시만</b> 재시도한다.
   *
   * <p><b>실패 시각을 지금 잰다.</b> 주기가 시작한 시각을 쓰면 푸시에 걸린 시간만큼 백오프가 짧아진다.
   *
   * <p><b>던지지 않는다.</b> 일꾼 스레드에서 새면 아무도 받지 않고 로그에도 남지 않는다.
   */
  private void pushAndRecord(NotificationDelivery delivery) {
    Long outboxId = delivery.outboxId();

    try {
      pushSender.send(delivery);
      notificationDispatchService.markDelivered(outboxId);

    } catch (PermanentPushException permanent) {
      abandon(outboxId, nowInUtc(), permanent);

    } catch (Exception exception) {
      recordFailure(outboxId, nowInUtc(), exception);
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
