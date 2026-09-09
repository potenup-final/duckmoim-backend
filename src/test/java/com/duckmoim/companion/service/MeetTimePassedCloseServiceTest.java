package com.duckmoim.companion.service;

import static com.duckmoim.companion.CompanionPostFixture.aCompanionPost;
import static org.assertj.core.api.Assertions.assertThat;

import com.duckmoim.companion.domain.ClosedReason;
import com.duckmoim.companion.domain.PostStatus;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 마감 배치의 멱등성과 동시 실행 (PO-14).
 *
 * <p><b>{@code @Transactional} 이 없다.</b> 테스트 컨벤션이 <i>"별도 스레드는 테스트의 트랜잭션에 참여하지 않아서, 롤백을 걸면 테스트가 항상
 * 통과하는 상태가 된다"</i> 고 못박았고, 멱등성도 <b>커밋된 상태를 다시 읽어야</b> 검증된다 — 롤백 안에서는 두 번째 호출이 첫 번째의 결과를 보는 것이 트랜잭션
 * 덕인지 저장 덕인지 갈리지 않는다. 그래서 넣은 행을 손으로 지운다.
 *
 * <p><b>현재 시각을 2026년 6월로 잡는다.</b> {@code V21} 이 넣어둔 시드 세 글의 만남시각이 「마이그레이션 시점 + 3·5·7일」이라 실행 날짜에 따라
 * 움직이는데, 그 값이 항상 오늘 이후이므로 과거의 한 점을 현재로 잡으면 시드가 배치 대상에 들어오지 않는다. 시드를 지우지 않는 이유는 다른 테스트가 그 세 글을 쓰기
 * 때문이다.
 */
@SpringBootTest
class MeetTimePassedCloseServiceTest {

  private static final LocalDateTime NOW = LocalDateTime.of(2026, 6, 1, 0, 0);
  private static final LocalDateTime PASSED = NOW.minusDays(1);
  private static final LocalDateTime NOT_PASSED = NOW.plusDays(1);

  private static final int CHUNK = 100;
  private static final int POSTS = 20;

  @Autowired private MeetTimePassedCloseService meetTimePassedCloseService;
  @Autowired private JdbcTemplate jdbc;

  private final List<Long> inserted = new ArrayList<>();

  /** 롤백이 없으니 손으로 지운다. 넣은 행만 지워야 시드가 남는다. */
  @AfterEach
  void tearDown() {
    inserted.forEach(id -> jdbc.update("DELETE FROM companion_post WHERE id = ?", id));
    inserted.clear();
  }

  @DisplayName("만남시각이 지난 모집글이 만남시각 경과로 마감된다.")
  @Test
  void closeChunk() {
    long postId = openPost(PASSED);

    int closed = meetTimePassedCloseService.closeChunk(NOW, CHUNK);

    assertThat(closed).isEqualTo(1);
    assertThat(statusOf(postId)).isEqualTo(PostStatus.CLOSED.name());
    assertThat(closedReasonOf(postId)).isEqualTo(ClosedReason.MEET_TIME_PASSED.name());
  }

  @DisplayName("만남시각이 남은 모집글은 모집중으로 남는다.")
  @Test
  void closeChunk_meetAtHasNotPassed() {
    long postId = openPost(NOT_PASSED);

    int closed = meetTimePassedCloseService.closeChunk(NOW, CHUNK);

    assertThat(closed).isZero();
    assertThat(statusOf(postId)).isEqualTo(PostStatus.OPEN.name());
  }

  /**
   * PO-14 의 「멱등」이다.
   *
   * <p>배치는 주기마다 다시 도는 작업이라 <b>두 번째 실행이 첫 번째와 같은 데이터를 만난다.</b> 그때 아무 일도 일어나지 않아야 한 주기의 실패를 다음 주기가 대신
   * 처리할 수 있다.
   */
  @DisplayName("배치를 다시 돌려도 이미 마감된 글을 다시 세지 않는다.")
  @Test
  void closeChunk_isIdempotent() {
    long postId = openPost(PASSED);
    meetTimePassedCloseService.closeChunk(NOW, CHUNK);

    int closed = meetTimePassedCloseService.closeChunk(NOW, CHUNK);

    assertThat(closed).isZero();
    assertThat(closedReasonOf(postId)).isEqualTo(ClosedReason.MEET_TIME_PASSED.name());
  }

  /**
   * 방장이 만남시각 전에 닫은 글은 시간이 지나면 배치의 조건에도 걸린다.
   *
   * <p>사유가 덮이면 화면 배지가 「모집 완료」에서 「종료」로 바뀌어 <b>방장이 모집을 끝냈다는 사실이 지워진다.</b> 도메인 단위 테스트가 같은 것을 보지만, 여기서
   * 다시 보는 것은 <b>조회가 그 글을 아예 집지 않는지</b>를 함께 확인하기 때문이다 — 집어서 도메인이 거절하는 것과 집지 않는 것은 결과가 같고 비용이 다르다.
   */
  @DisplayName("방장이 마감한 글은 만남시각이 지나도 사유가 직접 마감으로 남는다.")
  @Test
  void closeChunk_keepsManualReason() {
    long postId = closedPost(PASSED);

    int closed = meetTimePassedCloseService.closeChunk(NOW, CHUNK);

    assertThat(closed).isZero();
    assertThat(closedReasonOf(postId)).isEqualTo(ClosedReason.MANUAL.name());
  }

  /**
   * PO-14 의 검증 기준이다 — 「다중 인스턴스에서 1회만 실행」.
   *
   * <p><b>세는 것은 닫힌 건수의 합이다.</b> 한 글이 두 번 닫혔다면 합이 글 수를 넘고, 하나도 못 닫았다면 모자란다. 조회가 {@code FOR UPDATE
   * SKIP LOCKED} 라 두 실행이 같은 행을 집지 않으므로 합은 정확히 글 수여야 한다.
   *
   * <p>스레드 둘이 곧 인스턴스 둘이다 — 잠금은 커넥션 단위라 같은 JVM 인지 아닌지가 결과를 바꾸지 않는다.
   */
  @DisplayName("배치를 동시에 두 번 돌려도 한 글이 한 번만 마감된다.")
  @Test
  void closeChunk_isConcurrent() throws Exception {
    // given
    for (int i = 0; i < POSTS; i++) {
      openPost(PASSED);
    }
    CountDownLatch start = new CountDownLatch(1);

    // when
    ExecutorService pool = Executors.newFixedThreadPool(2);
    int total;
    try {
      List<Callable<Integer>> runs = List.of(closingRun(start, CHUNK), closingRun(start, CHUNK));
      List<Future<Integer>> futures = runs.stream().map(pool::submit).toList();

      start.countDown();
      pool.shutdown();
      assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

      total = 0;
      for (Future<Integer> future : futures) {
        total += future.get();
      }
    } finally {
      pool.shutdownNow();
    }

    // then
    assertThat(total).isEqualTo(POSTS);
    assertThat(openCount()).isZero();
  }

  private Callable<Integer> closingRun(CountDownLatch start, int chunk) {
    return () -> {
      start.await();
      return meetTimePassedCloseService.closeChunk(NOW, chunk);
    };
  }

  private long openPost(LocalDateTime meetAtUtc) {
    return remember(aCompanionPost().meetAt(meetAtUtc).insert(jdbc));
  }

  private long closedPost(LocalDateTime meetAtUtc) {
    return remember(
        aCompanionPost()
            .meetAt(meetAtUtc)
            .status(PostStatus.CLOSED)
            .closedReason(ClosedReason.MANUAL)
            .insert(jdbc));
  }

  private long remember(long postId) {
    inserted.add(postId);
    return postId;
  }

  private String statusOf(long postId) {
    return column(postId, "status");
  }

  private String closedReasonOf(long postId) {
    return column(postId, "closed_reason");
  }

  private String column(long postId, String name) {
    Map<String, Object> row =
        jdbc.queryForMap("SELECT status, closed_reason FROM companion_post WHERE id = ?", postId);

    return (String) row.get(name);
  }

  /** 넣은 글 중 아직 모집중인 것. 시드는 세지 않는다. */
  private int openCount() {
    return inserted.stream()
        .map(this::statusOf)
        .filter(PostStatus.OPEN.name()::equals)
        .toList()
        .size();
  }
}
