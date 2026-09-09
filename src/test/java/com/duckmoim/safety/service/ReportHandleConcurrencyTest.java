package com.duckmoim.safety.service;

import static com.duckmoim.safety.ReportFixture.aReport;
import static org.assertj.core.api.Assertions.assertThat;

import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.safety.domain.ReportStatus;
import com.duckmoim.safety.domain.ReportTargetType;
import com.duckmoim.safety.exception.ReportErrorCode;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 같은 신고를 둘이 동시에 붙잡는 것을 막는다 (AD-03).
 *
 * <p><b>{@code PROCESSING} 이 존재하는 이유가 이 검사다.</b> 도메인-모델링.md 「6. 라이프사이클」이 <i>"관리자가 넷이고 백오피스가 창구
 * 하나뿐이라 「처리 중」이 없으면 같은 건을 동시에 붙잡고 각자 조치한다"</i> 고 적었다. 전이표만으로는 그 문장이 성립하지 않는다 — 잠그지 않은 읽기라 둘 다
 * {@code PENDING} 을 보고 둘 다 통과한다.
 *
 * <p><b>{@code @Transactional} 이 없다.</b> 테스트 컨벤션이 <i>"별도 스레드는 테스트의 트랜잭션에 참여하지 않아서, 롤백을 걸면 테스트가 항상
 * 통과하는 상태가 된다"</i> 고 못박았다. 그래서 넣은 데이터를 손으로 지운다. {@code ReportConcurrencyTest} 와 같은 모양이다.
 *
 * <p>PR #86 리뷰에서 지적받아 더한 검사다.
 */
@SpringBootTest
class ReportHandleConcurrencyTest {

  private static final long ADMIN_ID = 6L;
  private static final long REPORTER_ID = 4L;
  private static final long TARGET_ID = 9931L;

  @Autowired private ReportHandleService reportHandleService;
  @Autowired private JdbcTemplate jdbc;

  private long reportId;

  @BeforeEach
  void setUp() {
    reportId =
        aReport()
            .reporterId(REPORTER_ID)
            .target(ReportTargetType.USER, TARGET_ID)
            .status(ReportStatus.PENDING)
            .insert(jdbc);
  }

  /** 롤백이 없으니 손으로 지운다. 남기면 다음 실행이 중복으로 걸린다. */
  @AfterEach
  void tearDown() {
    jdbc.update("DELETE FROM report WHERE id = ?", reportId);
  }

  @DisplayName("같은 신고를 동시에 잡으면 한 명만 성공한다.")
  @Test
  void handle_isConcurrent() throws Exception {
    int threads = 10;
    CountDownLatch ready = new CountDownLatch(threads);
    CountDownLatch start = new CountDownLatch(1);
    AtomicInteger accepted = new AtomicInteger();
    AtomicInteger rejected = new AtomicInteger();
    AtomicReference<BusinessException> rejection = new AtomicReference<>();

    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      for (int i = 0; i < threads; i++) {
        pool.submit(
            () -> {
              ready.countDown();
              try {
                start.await();
                reportHandleService.handle(
                    new ReportHandleCommand(
                        reportId, ReportStatus.PROCESSING, null, null, ADMIN_ID));
                accepted.incrementAndGet();
              } catch (BusinessException e) {
                rejected.incrementAndGet();
                rejection.set(e);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            });
      }

      ready.await();
      start.countDown();
      pool.shutdown();
      assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
    } finally {
      pool.shutdownNow();
    }

    assertThat(accepted.get()).isEqualTo(1);
    assertThat(rejected.get()).isEqualTo(threads - 1);
    assertThat(rejection.get()).isNotNull();
    assertThat(rejection.get().getErrorCode())
        .isEqualTo(ReportErrorCode.REPORT_TRANSITION_NOT_ALLOWED);
  }

  /** 늦게 온 쪽이 덮어쓰면 「누가 처리했는가」가 사실과 달라진다 (도메인 1.1 각주의 이력). */
  @DisplayName("동시에 종결하려 해도 처리자는 한 명으로 남는다.")
  @Test
  void handle_keepsSingleHandler() throws Exception {
    int threads = 6;
    CountDownLatch ready = new CountDownLatch(threads);
    CountDownLatch start = new CountDownLatch(1);
    AtomicInteger accepted = new AtomicInteger();

    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      for (int i = 0; i < threads; i++) {
        long adminId = ADMIN_ID + i;
        pool.submit(
            () -> {
              ready.countDown();
              try {
                start.await();
                reportHandleService.handle(
                    new ReportHandleCommand(reportId, ReportStatus.RESOLVED, null, null, adminId));
                accepted.incrementAndGet();
              } catch (BusinessException e) {
                // 전이표에 없는 요청이다. 개수만 센다.
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            });
      }

      ready.await();
      start.countDown();
      pool.shutdown();
      assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
    } finally {
      pool.shutdownNow();
    }

    assertThat(accepted.get()).isEqualTo(1);
    assertThat(storedStatus()).isEqualTo(ReportStatus.RESOLVED.name());
    assertThat(storedHandlerCount()).isEqualTo(1);
  }

  private String storedStatus() {
    return jdbc.queryForObject("SELECT status FROM report WHERE id = ?", String.class, reportId);
  }

  private int storedHandlerCount() {
    List<Integer> counts =
        jdbc.queryForList(
            "SELECT COUNT(DISTINCT handled_by) FROM report WHERE id = ?", Integer.class, reportId);

    return counts.get(0);
  }
}
