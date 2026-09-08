package com.duckmoim.safety.service;

import static com.duckmoim.companion.CompanionPostFixture.aCompanionPost;
import static org.assertj.core.api.Assertions.assertThat;

import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.safety.domain.ReportReason;
import com.duckmoim.safety.domain.ReportTargetType;
import com.duckmoim.safety.exception.ReportErrorCode;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
 * 동일 대상 중복 접수 차단의 이중 방어 (SF-01).
 *
 * <p><b>{@code @Transactional} 이 없다.</b> 테스트 컨벤션이 <i>"별도 스레드는 테스트의 트랜잭션에 참여하지 않아서, 롤백을 걸면 테스트가 항상
 * 통과하는 상태가 된다"</i> 고 못박았다. 그래서 넣은 데이터를 손으로 지운다.
 *
 * <p><b>사전 조회만으로는 뚫린다.</b> 두 스레드가 같은 순간에 조회하면 둘 다 「아직 신고 안 했다」를 받는다. 실제 차단은 V34 의 유니크 제약이 하고, 서비스가
 * 그 위반을 409 로 옮긴다 — 도메인 3.3 이 {@code I-01} 에 대해 정한 처리 방식과 같은 모양이다.
 */
@SpringBootTest
class ReportConcurrencyTest {

  private static final long REPORTER_ID = 2L;

  @Autowired private ReportCommandService reportCommandService;
  @Autowired private JdbcTemplate jdbc;

  private long postId;

  @BeforeEach
  void setUp() {
    postId = aCompanionPost().insert(jdbc);
  }

  /** 롤백이 없으니 손으로 지운다. 남기면 다음 테스트가 중복으로 걸린다. */
  @AfterEach
  void tearDown() {
    jdbc.update("DELETE FROM report WHERE reporter_id = ?", REPORTER_ID);
    jdbc.update("DELETE FROM companion_post WHERE id = ?", postId);
  }

  @DisplayName("동일 대상을 동시에 신고하면 한 건만 접수된다.")
  @Test
  void report_isConcurrent() throws Exception {
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
                reportCommandService.report(
                    new ReportCommand(
                        REPORTER_ID,
                        ReportTargetType.POST,
                        postId,
                        ReportReason.ADVERTISEMENT,
                        null));
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
      assertThat(pool.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    } finally {
      pool.shutdownNow();
    }

    assertThat(accepted.get()).isEqualTo(1);
    assertThat(rejected.get()).isEqualTo(threads - 1);
    assertThat(rejection.get()).isNotNull();
    assertThat(rejection.get().getErrorCode()).isEqualTo(ReportErrorCode.REPORT_DUPLICATED);
    assertThat(storedReportCount()).isEqualTo(1);
  }

  private int storedReportCount() {
    List<Integer> counts =
        jdbc.queryForList(
            "SELECT COUNT(*) FROM report WHERE reporter_id = ? AND target_type = 'POST' AND target_id = ?",
            Integer.class,
            REPORTER_ID,
            postId);
    return counts.get(0);
  }
}
