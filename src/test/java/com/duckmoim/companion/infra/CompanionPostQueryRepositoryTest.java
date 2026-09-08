package com.duckmoim.companion.infra;

import static com.duckmoim.companion.CommentFixture.aComment;
import static com.duckmoim.companion.CompanionPostFixture.aCompanionPost;
import static org.assertj.core.api.Assertions.assertThat;

import com.duckmoim.companion.domain.CommentStatus;
import com.duckmoim.companion.domain.PostCursor;
import com.duckmoim.companion.domain.PostListQuery;
import com.duckmoim.companion.domain.PostStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 목록의 정렬과 커서 경계 (PO-08), 그리고 상세 한 건 (PO-11).
 *
 * <p>실제 MySQL 로 돈다. 커서 경계는 정렬과 튜플 비교가 개입해서 mock 으로는 검증되지 않고, 테스트 컨벤션이 H2 도 금지했다.
 *
 * <p><b>만남시각을 손으로 고정한다.</b> PO-08 의 검증 기준이 「누락·중복 없음」이고 그것을 보려면 <i>정렬 키가 같은 데이터</i>가 있어야 한다.
 *
 * <p><b>시드를 지우고 시작한다.</b> V21 이 넣어둔 세 글이 목록에 섞이면 순서 단언이 무엇을 검증하는지 읽히지 않는다. {@code @Transactional}
 * 이라 롤백되고, {@code EventQueryServiceTest} 가 같은 방식이다.
 *
 * <p>방장은 V11 시드를 쓴다 — 1 방장 · 4 제3자.
 */
@SpringBootTest
@Transactional
class CompanionPostQueryRepositoryTest {

  private static final LocalDateTime BASE = LocalDateTime.of(2026, 10, 1, 0, 0);

  private static final String SEEDED_EVENT_EXTERNAL_ID = "pg_8417";

  @Autowired private CompanionPostRepository companionPostRepository;
  @Autowired private CommentRepository commentRepository;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void setUp() {
    jdbc.update("DELETE FROM comment");
    jdbc.update("DELETE FROM companion_post");
  }

  @DisplayName("만남시각 임박순으로 읽는다.")
  @Test
  void findSlice() {
    long third = post(BASE.plusDays(3));
    long first = post(BASE.plusDays(1));
    long second = post(BASE.plusDays(2));

    List<AuthoredPost> found = companionPostRepository.findSlice(query(null, null, 20));

    assertThat(idsOf(found)).containsExactly(first, second, third);
  }

  /** id 가 정렬 키에 없으면 이 순서가 정해지지 않는다. 그 자리가 곧 페이지 경계의 누락 지점이다. */
  @DisplayName("만남시각이 같으면 id 순으로 읽는다.")
  @Test
  void findSlice_sameMeetAt() {
    long first = post(BASE);
    long second = post(BASE);
    long third = post(BASE);

    List<AuthoredPost> found = companionPostRepository.findSlice(query(null, null, 20));

    assertThat(idsOf(found)).containsExactly(first, second, third);
  }

  @DisplayName("커서 다음 건부터 읽어 페이지 경계에서 누락도 중복도 없다.")
  @Test
  void findSlice_afterCursor() {
    long first = post(BASE);
    long second = post(BASE);
    long third = post(BASE);

    List<AuthoredPost> page = companionPostRepository.findSlice(query(null, null, 1));
    List<AuthoredPost> next =
        companionPostRepository.findSlice(query(null, new PostCursor(BASE, first), 1));

    assertThat(idsOf(page)).containsExactly(first, second);
    assertThat(idsOf(next)).containsExactly(second, third);
  }

  @DisplayName("다음 페이지 유무를 알려고 한 건을 더 읽는다.")
  @Test
  void findSlice_readsOneMore() {
    post(BASE.plusDays(1));
    post(BASE.plusDays(2));
    post(BASE.plusDays(3));

    assertThat(companionPostRepository.findSlice(query(null, null, 2))).hasSize(3);
  }

  @DisplayName("status=OPEN 이면 마감된 글이 나오지 않는다.")
  @Test
  void findSlice_openOnly() {
    long open = post(BASE.plusDays(1));
    closedPost(BASE.plusDays(2));

    List<AuthoredPost> found = companionPostRepository.findSlice(query(PostStatus.OPEN, null, 20));

    assertThat(idsOf(found)).containsExactly(open);
  }

  /** 도메인 6장이 CLOSED 의 열람을 「가능」으로 정했고, PO-08 의 필터는 「모집중 / 전체」 둘이다. */
  @DisplayName("status 를 생략하면 마감된 글도 나온다.")
  @Test
  void findSlice_allStatuses() {
    long open = post(BASE.plusDays(1));
    long closed = closedPost(BASE.plusDays(2));

    List<AuthoredPost> found = companionPostRepository.findSlice(query(null, null, 20));

    assertThat(idsOf(found)).containsExactly(open, closed);
  }

  @DisplayName("행사를 고르지 않은 글도 목록에 남는다.")
  @Test
  void findSlice_hasNoEvent() {
    long withoutEvent = post(BASE);

    List<AuthoredPost> found = companionPostRepository.findSlice(query(null, null, 20));

    assertThat(idsOf(found)).containsExactly(withoutEvent);
    assertThat(found.get(0).eventExternalId()).isNull();
  }

  /** 응답의 eventId 는 숫자 PK 가 아니라 외부 식별자다 (API-설계.md 「2-3. 행사 (Catalog)」). */
  @DisplayName("붙은 행사의 외부 식별자를 함께 읽는다.")
  @Test
  void findSlice_hasEvent() {
    long eventId = seededEventId();
    aCompanionPost().meetAt(BASE).event(eventId, "에이티즈 팝업", "https://cdn.test/1.webp").insert(jdbc);

    List<AuthoredPost> found = companionPostRepository.findSlice(query(null, null, 20));

    assertThat(found.get(0).eventExternalId()).isEqualTo(SEEDED_EVENT_EXTERNAL_ID);
  }

  @DisplayName("방장의 닉네임과 최근 접속 시각을 함께 읽는다.")
  @Test
  void findSlice_hasHost() {
    post(BASE);

    List<AuthoredPost> found = companionPostRepository.findSlice(query(null, null, 20));

    assertThat(found.get(0).nickname())
        .isEqualTo(jdbc.queryForObject("SELECT nickname FROM user WHERE id = 1", String.class));
  }

  @DisplayName("모집글 한 건을 방장과 함께 읽는다.")
  @Test
  void findAuthored() {
    long postId = post(BASE);

    Optional<AuthoredPost> found = companionPostRepository.findAuthored(postId);

    assertThat(found).isPresent();
    assertThat(found.get().post().getId()).isEqualTo(postId);
    assertThat(found.get().nickname()).isNotNull();
  }

  @DisplayName("없는 모집글은 비어 있다.")
  @Test
  void findAuthored_isMissing() {
    assertThat(companionPostRepository.findAuthored(404L)).isEmpty();
  }

  @DisplayName("댓글 수는 비밀을 포함하고 삭제·블라인드를 제외한다.")
  @Test
  void countActiveByPostIds() {
    long postId = post(BASE);
    aComment().postId(postId).insert(jdbc);
    aComment().postId(postId).secret(true).insert(jdbc);
    aComment().postId(postId).status(CommentStatus.DELETED).insert(jdbc);
    aComment().postId(postId).status(CommentStatus.BLINDED).insert(jdbc);

    assertThat(commentRepository.countActiveByPostIds(List.of(postId))).containsEntry(postId, 2L);
  }

  @DisplayName("대댓글도 댓글 수에 포함한다.")
  @Test
  void countActiveByPostIds_countsReplies() {
    long postId = post(BASE);
    long rootId = aComment().postId(postId).insert(jdbc);
    aComment().postId(postId).parentId(rootId).insert(jdbc);

    assertThat(commentRepository.countActiveByPostIds(List.of(postId))).containsEntry(postId, 2L);
  }

  @DisplayName("댓글이 없는 모집글은 집계에 아예 없다.")
  @Test
  void countActiveByPostIds_hasNoComment() {
    long postId = post(BASE);

    assertThat(commentRepository.countActiveByPostIds(List.of(postId))).isEmpty();
  }

  @DisplayName("셀 모집글이 없으면 빈 집계다.")
  @Test
  void countActiveByPostIds_isEmptyInput() {
    assertThat(commentRepository.countActiveByPostIds(List.of())).isEmpty();
  }

  /** V3 시드의 행사를 쓴다. 조인이 외부 식별자를 풀어 오는지만 보면 되고, 행사를 새로 넣을 이유가 없다. */
  private long seededEventId() {
    return jdbc.queryForObject(
        "SELECT id FROM event WHERE external_id = ?", Long.class, SEEDED_EVENT_EXTERNAL_ID);
  }

  private long post(LocalDateTime meetAt) {
    return aCompanionPost().meetAt(meetAt).insert(jdbc);
  }

  private long closedPost(LocalDateTime meetAt) {
    return aCompanionPost().meetAt(meetAt).status(PostStatus.CLOSED).insert(jdbc);
  }

  private static PostListQuery query(PostStatus status, PostCursor cursor, int size) {
    return new PostListQuery(status, cursor, size);
  }

  private static List<Long> idsOf(List<AuthoredPost> found) {
    return found.stream().map(authored -> authored.post().getId()).toList();
  }
}
