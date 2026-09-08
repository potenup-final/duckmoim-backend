package com.duckmoim.companion.service;

import static com.duckmoim.companion.CompanionPostFixture.aCompanionPost;
import static org.assertj.core.api.Assertions.assertThat;

import com.duckmoim.companion.infra.CommentRepository;
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
 * 댓글 수 집계의 동시성 (CM-12 · I-11).
 *
 * <p><b>{@code @Transactional} 이 없다.</b> 테스트 컨벤션이 <i>"별도 스레드는 테스트의 트랜잭션에 참여하지 않아서, 롤백을 걸면 테스트가 항상
 * 통과하는 상태가 된다"</i> 고 못박았다. 그래서 넣은 데이터를 손으로 지운다.
 *
 * <p><b>여기서 증명하는 것은 카운터의 정확성이 아니라 카운터가 없다는 것이다.</b> 도메인-모델링.md 「3.2」가 <i>"댓글을 쓸 때 모집글 행을 갱신하지 않으므로
 * 동시에 몇 건이 들어와도 서로 기다리지 않는다"</i> 로 정했다. 저장된 숫자가 없으니 갱신 유실이 일어날 자리가 없고, 그래서 100건이 동시에 들어가면 100건이 그대로
 * 남는다.
 *
 * <p>어긋난다면 원인은 집계가 아니라 <b>작성 경로가 일부를 잃은 것</b>이다. 그래서 저장된 행 수와 집계 값을 함께 본다 — 둘 다 100 이어야 두 경로가 모두
 * 성했다고 말할 수 있다.
 *
 * <p><b>「전부 준비될 때까지 기다렸다가 동시에 출발」을 쓰지 않는다.</b> 태스크가 100개인데 풀이 16개면 앞의 16개만 준비 신호를 보내고 나머지 84개는 큐에서
 * 대기하므로, 그 신호를 100번 기다리면 영원히 안 끝난다. 제출을 마친 뒤 래치를 한 번 여는 방식이면 풀 크기만큼이 항상 겹쳐 돈다.
 */
@SpringBootTest
class CommentCountConcurrencyTest {

  private static final int COMMENTS = 100;
  private static final int THREADS = 16;

  /** V11 이 넣어둔 유저다. 작성자가 누구인지가 집계에 영향을 주지 않아 한 명으로 충분하다. */
  private static final long AUTHOR_ID = 2L;

  @Autowired private CommentCommandService commentCommandService;
  @Autowired private CommentRepository commentRepository;
  @Autowired private JdbcTemplate jdbc;

  private long postId;

  @BeforeEach
  void setUp() {
    postId = aCompanionPost().insert(jdbc);
  }

  /** 롤백이 없으니 손으로 지운다. 남기면 다음 테스트의 집계에 섞인다. */
  @AfterEach
  void tearDown() {
    jdbc.update("DELETE FROM comment WHERE post_id = ?", postId);
    jdbc.update("DELETE FROM companion_post WHERE id = ?", postId);
  }

  @DisplayName("댓글 100건을 동시에 작성해도 집계가 정확하다.")
  @Test
  void countActiveByPostIds_isConcurrent() throws Exception {
    // given — 제출을 마친 뒤 한꺼번에 풀어야 경합이 실제로 겹친다
    CountDownLatch start = new CountDownLatch(1);
    AtomicInteger written = new AtomicInteger();
    AtomicReference<Exception> failure = new AtomicReference<>();

    // when
    ExecutorService pool = Executors.newFixedThreadPool(THREADS);
    try {
      for (int i = 0; i < COMMENTS; i++) {
        int index = i;
        pool.submit(
            () -> {
              try {
                start.await();
                commentCommandService.write(
                    new CommentWriteCommand(postId, AUTHOR_ID, null, "동시 댓글 " + index, false));
                written.incrementAndGet();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              } catch (Exception e) {
                failure.set(e);
              }
            });
      }

      start.countDown();
      pool.shutdown();
      assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
    } finally {
      pool.shutdownNow();
    }

    // then
    assertThat(failure.get()).isNull();
    assertThat(written.get()).isEqualTo(COMMENTS);
    assertThat(storedCount()).isEqualTo(COMMENTS);
    assertThat(commentRepository.countActiveByPostIds(List.of(postId)))
        .containsEntry(postId, (long) COMMENTS);
  }

  private int storedCount() {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM comment WHERE post_id = ?", Integer.class, postId);
  }
}
