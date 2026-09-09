package com.duckmoim.safety.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.safety.domain.SanctionKind;
import com.duckmoim.safety.exception.SanctionErrorCode;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 한 회원에게 활성 제재가 둘이 되지 않는다 (AD-04).
 *
 * <p><b>이 티켓이 「활성 제재는 최대 하나」를 판단으로 세웠다.</b> 근거는 도메인-모델링.md 「6. 라이프사이클」의 제재 축이 {@code NONE} 에서만 출발하는
 * 상태 머신이라는 것이다. 사전 조회만으로는 그 주장이 코드에서 성립하지 않는다 — 두 스레드가 같은 순간에 조회하면 둘 다 「없다」를 받는다.
 *
 * <p><b>{@code @Transactional} 이 없다.</b> 테스트 컨벤션이 <i>"별도 스레드는 테스트의 트랜잭션에 참여하지 않아서, 롤백을 걸면 테스트가 항상
 * 통과하는 상태가 된다"</i> 고 못박았다. 그래서 넣은 데이터를 손으로 지운다.
 *
 * <p>PR #86 의 리뷰 지적(신고 처리의 같은 경합)에서 찾아 더한 검사다.
 *
 * <p>대상은 V11 시드의 3 번('답글덕후') — 다른 검사가 쓰는 2 번을 피한다.
 */
@SpringBootTest
class SanctionConcurrencyTest {

  private static final long ADMIN_ID = 6L;
  private static final long USER_ID = 3L;

  @Autowired private SanctionCommandService sanctionCommandService;
  @Autowired private JdbcTemplate jdbc;

  /** 롤백이 없으니 손으로 지운다. 남기면 다음 실행이 「이미 제재 중」으로 걸린다. */
  @AfterEach
  void tearDown() {
    jdbc.update("DELETE FROM audit_log WHERE target_id = ?", USER_ID);
    jdbc.update("DELETE FROM sanction WHERE user_id = ?", USER_ID);
  }

  @DisplayName("같은 회원을 동시에 제재하면 한 건만 걸린다.")
  @Test
  void sanction_isConcurrent() throws Exception {
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
                sanctionCommandService.sanction(
                    new SanctionCommand(USER_ID, SanctionKind.WARNED, "약속 불이행", null, ADMIN_ID));
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
    assertThat(rejection.get().getErrorCode()).isEqualTo(SanctionErrorCode.SANCTION_ALREADY_ACTIVE);
    assertThat(storedSanctionCount()).isEqualTo(1);
  }

  /** 감사 로그는 고칠 수 없다 (I-13). 막힌 요청이 장부에 오르면 되돌릴 방법이 없다. */
  @DisplayName("막힌 요청은 감사 로그에도 남지 않는다.")
  @Test
  void sanction_isConcurrentRecordsOnce() throws Exception {
    int threads = 8;
    CountDownLatch ready = new CountDownLatch(threads);
    CountDownLatch start = new CountDownLatch(1);

    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      for (int i = 0; i < threads; i++) {
        pool.submit(
            () -> {
              ready.countDown();
              try {
                start.await();
                sanctionCommandService.sanction(
                    new SanctionCommand(USER_ID, SanctionKind.BANNED, "약속 불이행", null, ADMIN_ID));
              } catch (BusinessException e) {
                // 막힌 요청이다. 개수는 아래에서 감사 로그로 센다.
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

    assertThat(storedSanctionCount()).isEqualTo(1);
    assertThat(storedAuditCount()).isEqualTo(1);
  }

  private int storedSanctionCount() {
    return count("SELECT COUNT(*) FROM sanction WHERE user_id = ?");
  }

  private int storedAuditCount() {
    return count("SELECT COUNT(*) FROM audit_log WHERE kind = 'SANCTION' AND target_id = ?");
  }

  private int count(String sql) {
    List<Integer> counts = jdbc.queryForList(sql, Integer.class, USER_ID);

    return counts.get(0);
  }
}
