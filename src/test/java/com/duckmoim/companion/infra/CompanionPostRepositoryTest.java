package com.duckmoim.companion.infra;

import static com.duckmoim.companion.CompanionPostFixture.aCompanionPost;
import static org.assertj.core.api.Assertions.assertThat;

import com.duckmoim.companion.domain.ClosedReason;
import com.duckmoim.companion.domain.CompanionPost;
import com.duckmoim.companion.domain.PostStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 마감 배치가 집는 대상 (PO-14).
 *
 * <p>실제 MySQL 로 돈다. {@code FOR UPDATE SKIP LOCKED} 와 {@code LIMIT} 가 붙는 쿼리라 mock 으로는 무엇이 오는지 검증되지
 * 않고, 테스트 컨벤션이 H2 도 금지했다.
 *
 * <p><b>잠금이 여러 인스턴스에서 어떻게 도는지는 여기서 보지 않는다.</b> 그것은 트랜잭션 둘이 필요해 {@code @Transactional} 을 쓸 수 없고,
 * {@code MeetTimePassedCloseServiceTest} 가 스레드 둘로 검증한다. 여기는 <b>무엇을 집는지</b>만 본다.
 *
 * <p><b>시드를 지우고 시작한다.</b> V21 이 넣어둔 세 글이 섞이면 단언이 무엇을 검증하는지 읽히지 않는다. {@code
 * CompanionPostQueryRepositoryTest} 가 같은 방식이다.
 */
@SpringBootTest
@Transactional
class CompanionPostRepositoryTest {

  private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 1, 0, 0);

  @Autowired private CompanionPostRepository companionPostRepository;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void setUp() {
    jdbc.update("DELETE FROM comment");
    jdbc.update("DELETE FROM companion_post");
  }

  @DisplayName("만남시각이 지난 모집중 글을 집는다.")
  @Test
  void findOpenPostsWithMeetTimePassed() {
    long passed = openPost(NOW.minusMinutes(1));

    List<CompanionPost> found = find(20);

    assertThat(idsOf(found)).containsExactly(passed);
  }

  @DisplayName("만남시각이 남은 글은 집지 않는다.")
  @Test
  void findOpenPostsWithMeetTimePassed_meetAtHasNotPassed() {
    openPost(NOW.plusMinutes(1));

    assertThat(find(20)).isEmpty();
  }

  /** 만남시각과 현재 시각이 같으면 아직 지나지 않은 것이다. 도메인의 판정과 같은 경계여야 한다. */
  @DisplayName("만남시각이 현재 시각과 같으면 집지 않는다.")
  @Test
  void findOpenPostsWithMeetTimePassed_meetAtIsNow() {
    openPost(NOW);

    assertThat(find(20)).isEmpty();
  }

  /**
   * 배치가 재실행돼도 이미 닫힌 글을 다시 집지 않는다는 뜻이다 (PO-14 「멱등」).
   *
   * <p>도메인이 멱등이라 집어도 결과는 같지만, 집는 순간 <b>닫을 것이 없는 트랜잭션이 매 주기 커진다.</b> 마감된 글은 쌓이기만 하고 줄지 않는다.
   */
  @DisplayName("이미 마감된 글은 만남시각이 지나도 집지 않는다.")
  @Test
  void findOpenPostsWithMeetTimePassed_postIsAlreadyClosed() {
    closedPost(NOW.minusDays(1), ClosedReason.MANUAL);
    closedPost(NOW.minusDays(1), ClosedReason.MEET_TIME_PASSED);

    assertThat(find(20)).isEmpty();
  }

  /** 오래 지난 글부터 닫는다. 밀린 글이 청크보다 많을 때 어느 것이 먼저 닫히는지가 정해져 있어야 한다. */
  @DisplayName("만남시각이 오래 지난 글부터 집는다.")
  @Test
  void findOpenPostsWithMeetTimePassed_ordersByMeetAt() {
    long second = openPost(NOW.minusDays(2));
    long third = openPost(NOW.minusDays(1));
    long first = openPost(NOW.minusDays(3));

    assertThat(idsOf(find(20))).containsExactly(first, second, third);
  }

  @DisplayName("청크보다 많이 밀려 있어도 청크 크기만큼만 집는다.")
  @Test
  void findOpenPostsWithMeetTimePassed_limitsToChunk() {
    openPost(NOW.minusDays(3));
    openPost(NOW.minusDays(2));
    openPost(NOW.minusDays(1));

    assertThat(find(2)).hasSize(2);
  }

  private List<CompanionPost> find(int chunk) {
    return companionPostRepository.findOpenPostsWithMeetTimePassed(NOW, PageRequest.ofSize(chunk));
  }

  private long openPost(LocalDateTime meetAtUtc) {
    return aCompanionPost().meetAt(meetAtUtc).insert(jdbc);
  }

  private long closedPost(LocalDateTime meetAtUtc, ClosedReason reason) {
    return aCompanionPost()
        .meetAt(meetAtUtc)
        .status(PostStatus.CLOSED)
        .closedReason(reason)
        .insert(jdbc);
  }

  private static List<Long> idsOf(List<CompanionPost> posts) {
    return posts.stream().map(CompanionPost::getId).toList();
  }
}
