package com.duckmoim.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 워커 둘이 같은 아웃박스를 훑을 때 무슨 일이 생기는가 (NT-04).
 *
 * <p><b>이 클래스가 이 티켓의 측정 장치다.</b> 티켓이 선점 방식을 「실제로 돌려보고 고른다」로 정했고, 방식을 바꿔도 이 장치는 그대로 쓴다 — 재는 자리가 한
 * 곳이어야 숫자를 비교할 수 있다.
 *
 * <p><b>배치가 아니라 서비스를 돌린다.</b> {@code NotificationDispatchBatch} 는 보낸 건수만 세고 <b>겹쳐서 헛일한 횟수</b>를 세지
 * 않는다. 지금 구조에서 새는 것이 중복 발송이 아니라 헛일이므로, 그 값을 못 보면 선점이 실제로 무엇을 줄였는지 알 수 없다.
 *
 * <p><b>{@code @Transactional} 을 붙이지 않는다.</b> 별도 스레드가 테스트의 트랜잭션에 참여하지 않아, 붙이면 롤백이 스레드가 만든 행을 지우지
 * 못하고 검사가 <b>항상 통과하는 상태</b>가 된다 (테스트 컨벤션 「동시성」).
 *
 * <p>수신자와 아웃박스 번호를 남이 안 쓰는 대역에서 뗀다. 이 검사는 표 전체를 훑고 「정확히 이만큼」을 단언한다.
 */
@SpringBootTest
class NotificationWorkerContentionTest {

  private static final Logger log = LoggerFactory.getLogger(NotificationWorkerContentionTest.class);

  private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 12, 0, 0);

  /** 한 워커가 한 번에 집는 건수. 운영 기본값과 같다. */
  private static final int CHUNK = 100;

  /**
   * 한 워커가 도는 최대 바퀴.
   *
   * <p>조회 조건과 처리 결과가 어긋나면 같은 건을 무한히 다시 집는다. 운영 배치에 같은 이유의 상한이 있고 (`MAX_CHUNKS`), 여기서도 없으면 실패가 무한
   * 루프로 나타나 원인을 못 좁힌다.
   */
  private static final int MAX_ROUNDS = 100;

  private static final long RECIPIENT_ID = 92_001L;

  private final AtomicLong outboxIds = new AtomicLong(92_000);

  @Autowired private NotificationDispatchService notificationDispatchService;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  @AfterEach
  void clean() {
    jdbc.update("DELETE FROM notification");
    jdbc.update("DELETE FROM notification_outbox_dlq");
    jdbc.update("DELETE FROM notification_outbox");
  }

  /**
   * {@code NT-04} 의 검증 기준이다 — <i>워커 2개 동시 구동 시 중복 발송 0건</i>.
   *
   * <p><b>선점이 없는 지금도 이 검사는 통과한다.</b> {@code notification} 표의 {@code outbox_id} 유니크가 둘째 알림을 막기 때문이다.
   * 그래서 이 검사만으로는 선점이 들어왔는지 알 수 없고, 아래 기준선 검사가 그 자리를 맡는다.
   */
  @DisplayName("워커 둘이 동시에 돌아도 알림이 한 번만 생긴다.")
  @Test
  void dispatch_withTwoWorkers() {
    givenPendingOutbox(200);

    Run run = runWorkers(2);

    assertThat(run.sent()).as("보낸 건수가 쌓인 건수와 같아야 한다").isEqualTo(200);
    assertThat(notificationCount()).isEqualTo(200);
    assertThat(distinctOutboxCount()).as("한 발행에서 알림이 둘 나오면 안 된다").isEqualTo(200);
  }

  /**
   * 선점이 들어오면 <b>줄어야 하는 숫자</b>를 남긴다.
   *
   * <p>헛일은 「남이 이미 처리한 건을 집어서 아무것도 못 한 횟수」다. 지금은 두 워커가 같은 목록을 받으므로 이 값이 크고, 선점이 들어오면 0 에 가까워져야 한다. 이
   * 검사는 값을 <b>기록</b>하는 것이 목적이라 상한을 걸지 않는다 — 걸면 선점 방식을 바꿀 때마다 기대값을 손봐야 하고, 그러면 기록이 아니라 잔소리가 된다.
   */
  @DisplayName("워커 둘이 겹쳐 집은 횟수를 기록한다.")
  @Test
  void dispatch_measuresWastedPicks() {
    givenPendingOutbox(200);

    Run run = runWorkers(2);

    log.info(
        "[측정] 워커 2 · 건수 200 → 보냄 {}, 조용히 넘김 {}, 충돌 {}, 걸린 시간 {}ms",
        run.sent(),
        run.skipped(),
        run.collided(),
        run.elapsedMillis());

    assertThat(run.sent() + run.skipped() + run.collided())
        .as("집은 횟수는 보낸 것과 헛일의 합이다")
        .isGreaterThanOrEqualTo(200);
  }

  /** 워커를 동시에 띄워 한 명도 먼저 출발하지 않게 한다. 순차로 돌면 경쟁이 아예 안 생긴다. */
  private Run runWorkers(int workers) {
    ExecutorService pool = Executors.newFixedThreadPool(workers);
    CountDownLatch start = new CountDownLatch(1);

    try {
      List<Future<Run>> futures =
          java.util.stream.IntStream.range(0, workers)
              .mapToObj(i -> pool.submit(() -> workOnce(start)))
              .toList();

      long began = System.nanoTime();
      start.countDown();

      int sent = 0;
      int skipped = 0;
      int collided = 0;
      for (Future<Run> future : futures) {
        Run run = future.get(60, TimeUnit.SECONDS);
        sent += run.sent();
        skipped += run.skipped();
        collided += run.collided();
      }

      return new Run(sent, skipped, collided, (System.nanoTime() - began) / 1_000_000);

    } catch (Exception e) {
      throw new IllegalStateException("워커를 돌리지 못했다", e);
    } finally {
      pool.shutdownNow();
    }
  }

  /**
   * 워커 하나가 보낼 것이 없어질 때까지 도는 것. <b>운영 배치의 반복을 그대로 옮긴 것이다.</b>
   *
   * <p><b>예외를 잡는 자리가 장치의 핵심이다.</b> 선점이 없으면 두 워커가 같은 행에서 부딪히고, 그때 진 쪽은 {@code false} 를 받는 것이 아니라
   * <b>유니크 제약 위반으로 예외를 맞는다</b> — 알림을 만들려는 순간 이미 남이 만들어 둔 것이다. 운영에서는 {@code
   * NotificationDispatchBatch.dispatchOne} 이 그 예외를 잡아 실패 기록으로 넘기고, 그 기록이 「남이 보냈다」로 조용히 끝난다. 여기서 안
   * 잡으면 장치가 운영과 다른 것을 재게 된다.
   */
  private Run workOnce(CountDownLatch start) throws InterruptedException {
    start.await();

    int sent = 0;
    int skipped = 0;
    int collided = 0;

    for (int round = 0; round < MAX_ROUNDS; round++) {
      List<Long> ids = notificationDispatchService.findSendableIds(NOW, CHUNK);
      if (ids.isEmpty()) {
        break;
      }

      for (Long id : ids) {
        try {
          if (notificationDispatchService.dispatch(id)) {
            sent++;
          } else {
            skipped++;
          }
        } catch (Exception collision) {
          // 운영 배치와 같은 순서다 — 발송이 롤백된 뒤에 별 트랜잭션이 실패를 적으러 간다.
          notificationDispatchService.recordFailure(id, NOW);
          collided++;
        }
      }
    }

    return new Run(sent, skipped, collided, 0);
  }

  private void givenPendingOutbox(int rows) {
    for (int i = 0; i < rows; i++) {
      jdbc.update(
          """
          INSERT INTO notification_outbox
              (recipient_id, kind, post_id, comment_id, status, attempts, created_at, updated_at)
          VALUES (?, 'POST_COMMENTED', 10, ?, 'PENDING', 0, ?, ?)
          """,
          RECIPIENT_ID,
          outboxIds.getAndIncrement(),
          NOW,
          NOW);
    }
  }

  private int notificationCount() {
    return jdbc.queryForObject("SELECT COUNT(*) FROM notification", Integer.class);
  }

  private int distinctOutboxCount() {
    return jdbc.queryForObject("SELECT COUNT(DISTINCT outbox_id) FROM notification", Integer.class);
  }

  /**
   * 워커들이 돌고 난 결과. {@code elapsedMillis} 는 전체 합산에서만 뜻이 있다.
   *
   * <p><b>헛일을 둘로 나눈 것이 이 장치의 값이다.</b> 비용이 다르다 — {@code skipped} 는 읽고 나서 「내 것이 아니다」로 끝나는 것이지만,
   * {@code collided} 는 <b>알림 INSERT 가 유니크 제약에 걸려 트랜잭션이 롤백되고 실패 기록이 또 한 트랜잭션을 쓰는</b> 것이다. 선점이 없애야 하는
   * 것은 후자다.
   *
   * @param skipped 집었으나 이미 남이 처리해 조용히 넘어간 횟수
   * @param collided 알림을 만들다 유니크 제약에 부딪혀 롤백된 횟수
   */
  private record Run(int sent, int skipped, int collided, long elapsedMillis) {}
}
