package com.duckmoim.companion.service;

import static com.duckmoim.companion.CompanionPostFixture.aCompanionPost;
import static org.assertj.core.api.Assertions.assertThat;

import com.duckmoim.companion.domain.ClosedReason;
import com.duckmoim.companion.domain.PostStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;

/**
 * 마감 배치의 반복과 시각 기준, 그리고 주기가 실제로 등록되는지 (PO-14).
 *
 * <p><b>청크를 2로 줄인다.</b> 기본값 200 으로는 반복이 도는지 보려면 201건을 넣어야 하고, 그러면 테스트가 무엇을 검증하는지보다 데이터 만드는 코드가
 * 길어진다.
 *
 * <p><b>시계를 고정한다.</b> 「지난 글」과 「안 지난 글」의 경계가 검증 대상이라 놓아두면 같은 데이터가 어제와 오늘 다르게 나온다. 시계의 시간대를 {@code
 * Asia/Seoul} 로 두는 것은 {@code ClockConfig} 와 같게 맞추려는 것이다 — <b>배치가 그 시계에서 UTC 를 뽑아내는지가 검증 대상이다.</b>
 *
 * <p><b>{@code @Transactional} 이 없다.</b> 배치가 청크마다 트랜잭션을 열고 닫으므로 테스트가 트랜잭션을 쥐고 있으면 그 경계가 사라진다.
 */
@SpringBootTest(properties = "duckmoim.batch.post-close.chunk=2")
class MeetTimePassedCloseBatchTest {

  /** 고정된 현재 시각. UTC 로 2026-06-01 00:00, 같은 순간의 KST 는 09:00 이다. */
  private static final Instant NOW = Instant.parse("2026-06-01T00:00:00Z");

  private static final LocalDateTime PASSED = LocalDateTime.of(2026, 5, 31, 23, 0);

  /** UTC 로는 아직 오지 않았고 <b>KST 벽시계(09:00)로는 지난</b> 시각이다. */
  private static final LocalDateTime NOT_PASSED_ONLY_IN_UTC = LocalDateTime.of(2026, 6, 1, 5, 0);

  @TestConfiguration
  static class FixedClockConfig {

    @Bean
    @Primary
    Clock fixedClock() {
      return Clock.fixed(NOW, ZoneId.of("Asia/Seoul"));
    }
  }

  @Autowired private MeetTimePassedCloseBatch meetTimePassedCloseBatch;
  @Autowired private ScheduledTaskHolder scheduledTaskHolder;
  @Autowired private JdbcTemplate jdbc;

  private final List<Long> inserted = new ArrayList<>();

  /** 롤백이 없으니 손으로 지운다. 넣은 행만 지워야 시드가 남는다. */
  @AfterEach
  void tearDown() {
    inserted.forEach(id -> jdbc.update("DELETE FROM companion_post WHERE id = ?", id));
    inserted.clear();
  }

  /** 청크가 2 인데 다섯 건이다 — 한 번 도는 것으로는 끝나지 않는다. */
  @DisplayName("밀린 글이 청크보다 많아도 한 주기에 전부 마감된다.")
  @Test
  void closeMeetTimePassedPosts_drainsBeyondOneChunk() {
    for (int i = 0; i < 5; i++) {
      openPost(PASSED);
    }

    meetTimePassedCloseBatch.closeMeetTimePassedPosts();

    assertThat(statusesOf()).containsOnly(PostStatus.CLOSED.name());
    assertThat(closedReasonsOf()).containsOnly(ClosedReason.MEET_TIME_PASSED.name());
  }

  /**
   * 시계가 KST 인데 {@code meet_at} 은 UTC 로 저장된다는 사실이 이 배치에 직접 걸린다.
   *
   * <p>배치가 {@code LocalDateTime.now(clock)} 을 쓰면 KST 벽시계 09:00 이 들어가서 <b>UTC 09:00 이전에 만나는 글을 전부
   * 닫는다</b> — 매 실행마다 아홉 시간 안쪽의 글이 만나기 전에 사라진다.
   */
  @DisplayName("만남시각 판정은 UTC 로 한다 — KST 벽시계로는 만나기 전에 닫힐 글이 남는다.")
  @Test
  void closeMeetTimePassedPosts_comparesInUtc() {
    long passed = openPost(PASSED);
    long notPassed = openPost(NOT_PASSED_ONLY_IN_UTC);

    meetTimePassedCloseBatch.closeMeetTimePassedPosts();

    assertThat(statusOf(passed)).isEqualTo(PostStatus.CLOSED.name());
    assertThat(statusOf(notPassed)).isEqualTo(PostStatus.OPEN.name());
  }

  /**
   * 주기가 실제로 등록되는지 본다.
   *
   * <p>배치 코드가 아무리 맞아도 {@code @EnableScheduling} 이 없거나 cron 프로퍼티가 비면 <b>아무 일도 일어나지 않는데 테스트는 전부
   * 초록불이다.</b> 이 티켓에서 유일하게 「돌게 만드는 것」이 그 두 줄이라 그것을 못박는다.
   */
  @DisplayName("마감 배치가 주기 작업으로 등록된다.")
  @Test
  void closeMeetTimePassedPosts_isScheduled() {
    List<String> tasks =
        scheduledTaskHolder.getScheduledTasks().stream()
            .map(ScheduledTask::toString)
            .filter(task -> task.contains("closeMeetTimePassedPosts"))
            .toList();

    assertThat(tasks).hasSize(1);
  }

  private long openPost(LocalDateTime meetAtUtc) {
    long postId = aCompanionPost().meetAt(meetAtUtc).insert(jdbc);
    inserted.add(postId);

    return postId;
  }

  private List<String> statusesOf() {
    return inserted.stream().map(this::statusOf).toList();
  }

  private List<String> closedReasonsOf() {
    return inserted.stream()
        .map(
            id ->
                jdbc.queryForObject(
                    "SELECT closed_reason FROM companion_post WHERE id = ?", String.class, id))
        .toList();
  }

  private String statusOf(long postId) {
    return jdbc.queryForObject(
        "SELECT status FROM companion_post WHERE id = ?", String.class, postId);
  }
}
