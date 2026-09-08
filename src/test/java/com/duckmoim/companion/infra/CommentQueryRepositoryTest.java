package com.duckmoim.companion.infra;

import static com.duckmoim.companion.CommentFixture.aComment;
import static com.duckmoim.companion.CompanionPostFixture.aCompanionPost;
import static org.assertj.core.api.Assertions.assertThat;

import com.duckmoim.companion.domain.CommentCursor;
import com.duckmoim.companion.domain.CommentListQuery;
import com.duckmoim.companion.domain.CommentStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 목록 조회의 정렬과 커서 경계 (CM-06 · CM-07).
 *
 * <p>실제 MySQL 로 돈다. 커서 경계는 정렬과 튜플 비교가 개입해서 mock 으로는 검증되지 않고, 테스트 컨벤션이 H2 도 금지했다.
 *
 * <p><b>작성 시각을 손으로 고정한다.</b> CM-07 의 검증 기준이 「페이지 경계에서 대댓글 분리 없음」이고 그 앞에 <i>정렬 키가 같은 데이터</i>가 있어야
 * 한다. {@code UTC_TIMESTAMP(6)} 에 맡기면 마이크로초가 갈려 같은 시각이 만들어지지 않는다.
 *
 * <p>작성자는 V11 시드를 쓴다 — 1 방장 · 2 부모댓글작성자 · 3 대댓글작성자 · 4 제3자.
 */
@SpringBootTest
@Transactional
class CommentQueryRepositoryTest {

  private static final LocalDateTime BASE = LocalDateTime.of(2026, 9, 1, 0, 0);

  private static final long AUTHOR_ID = 2L;
  private static final long REPLIER_ID = 3L;

  @Autowired private CommentRepository commentRepository;
  @Autowired private JdbcTemplate jdbc;

  private long postId;

  @BeforeEach
  void setUp() {
    postId = aCompanionPost().insert(jdbc);
  }

  @DisplayName("루트 댓글이 작성 시간순으로 나온다.")
  @Test
  void findRootSlice() {
    long third = root(BASE.plusMinutes(3));
    long first = root(BASE.plusMinutes(1));
    long second = root(BASE.plusMinutes(2));

    List<AuthoredComment> roots = commentRepository.findRootSlice(query(null, 20));

    assertThat(idsOf(roots)).containsExactly(first, second, third);
  }

  @DisplayName("대댓글은 루트 목록에 섞이지 않는다.")
  @Test
  void findRootSlice_excludesReplies() {
    long rootId = root(BASE.plusMinutes(1));
    reply(rootId, BASE.plusMinutes(2));

    List<AuthoredComment> roots = commentRepository.findRootSlice(query(null, 20));

    assertThat(idsOf(roots)).containsExactly(rootId);
  }

  @DisplayName("다음 페이지 유무를 알려고 한 건을 더 읽는다.")
  @Test
  void findRootSlice_readsOneMore() {
    root(BASE.plusMinutes(1));
    root(BASE.plusMinutes(2));
    root(BASE.plusMinutes(3));

    assertThat(commentRepository.findRootSlice(query(null, 2))).hasSize(3);
  }

  /** CM-07 의 검증 기준이 걸린 자리다. createdAt 만으로 정렬하면 여기서 누락·중복이 난다. */
  @DisplayName("작성 시각이 같아도 id 가 순서를 정한다.")
  @Test
  void findRootSlice_hasSameCreatedAt() {
    long first = root(BASE);
    long second = root(BASE);
    long third = root(BASE);

    List<AuthoredComment> page = commentRepository.findRootSlice(query(null, 2));
    assertThat(idsOf(page)).containsExactly(first, second, third);

    List<AuthoredComment> next =
        commentRepository.findRootSlice(query(new CommentCursor(BASE, second), 2));

    assertThat(idsOf(next)).containsExactly(third);
  }

  @DisplayName("커서 다음부터 이어 읽고 앞 페이지를 다시 주지 않는다.")
  @Test
  void findRootSlice_afterCursor() {
    long first = root(BASE.plusMinutes(1));
    long second = root(BASE.plusMinutes(2));
    long third = root(BASE.plusMinutes(3));

    List<AuthoredComment> next =
        commentRepository.findRootSlice(query(new CommentCursor(BASE.plusMinutes(1), first), 20));

    assertThat(idsOf(next)).containsExactly(second, third);
  }

  @DisplayName("대댓글은 부모별로 작성 시간순이다.")
  @Test
  void findRepliesOf() {
    long firstRoot = root(BASE.plusMinutes(1));
    long secondRoot = root(BASE.plusMinutes(2));
    long laterReply = reply(firstRoot, BASE.plusMinutes(9));
    long earlierReply = reply(firstRoot, BASE.plusMinutes(4));
    long otherReply = reply(secondRoot, BASE.plusMinutes(5));

    List<AuthoredComment> replies =
        commentRepository.findRepliesOf(postId, List.of(firstRoot, secondRoot));

    assertThat(idsOf(replies)).containsExactly(earlierReply, laterReply, otherReply);
  }

  @DisplayName("루트가 없으면 대댓글도 조회하지 않는다.")
  @Test
  void findRepliesOf_hasNoParent() {
    assertThat(commentRepository.findRepliesOf(postId, List.of())).isEmpty();
  }

  /** 작성자를 ID 로만 참조하므로 닉네임과 아바타는 조인으로 얻는다 (도메인 3.2). */
  @DisplayName("작성자 정보를 함께 읽는다.")
  @Test
  void findRootSlice_carriesAuthor() {
    root(BASE);

    AuthoredComment found = commentRepository.findRootSlice(query(null, 20)).get(0);

    assertThat(found.comment().getAuthorId()).isEqualTo(AUTHOR_ID);
    assertThat(found.nickname()).isEqualTo("댓글덕후");
    assertThat(found.lastSeenAt()).isNotNull();
  }

  @DisplayName("댓글 한 건을 작성자와 함께 읽는다.")
  @Test
  void findAuthoredById() {
    long commentId = root(BASE);

    AuthoredComment found = commentRepository.findAuthoredById(commentId).orElseThrow();

    assertThat(found.comment().getId()).isEqualTo(commentId);
    assertThat(found.nickname()).isEqualTo("댓글덕후");
  }

  @DisplayName("비밀 댓글도 본문을 그대로 읽는다.")
  @Test
  void findAuthoredById_readsSecret() {
    long commentId =
        aComment().postId(postId).authorId(AUTHOR_ID).secret(true).createdAt(BASE).insert(jdbc);

    AuthoredComment found = commentRepository.findAuthoredById(commentId).orElseThrow();

    assertThat(found.comment().isSecret()).isTrue();
    assertThat(found.comment().getContent()).isNotBlank();
  }

  /**
   * 지운 댓글을 못 읽으면 신고당한 사람이 지우는 것으로 판정을 막을 수 있다 (API-설계.md 「2-5. 댓글 (Companion)」). 일반 조회 경로의 404 는
   * 그대로다.
   */
  @DisplayName("소프트 삭제·블라인드된 댓글도 읽는다.")
  @ParameterizedTest
  @EnumSource(
      value = CommentStatus.class,
      names = {"DELETED", "BLINDED"})
  void findAuthoredById_readsInactive(CommentStatus status) {
    long commentId =
        aComment().postId(postId).authorId(AUTHOR_ID).status(status).createdAt(BASE).insert(jdbc);

    AuthoredComment found = commentRepository.findAuthoredById(commentId).orElseThrow();

    assertThat(found.comment().getStatus()).isEqualTo(status);
    assertThat(found.comment().getContent()).isNotBlank();
  }

  @DisplayName("없는 댓글은 비어서 돌아온다.")
  @Test
  void findAuthoredById_hasNoComment() {
    assertThat(commentRepository.findAuthoredById(-1L)).isEmpty();
  }

  private long root(LocalDateTime createdAt) {
    return aComment().postId(postId).authorId(AUTHOR_ID).createdAt(createdAt).insert(jdbc);
  }

  private long reply(long parentId, LocalDateTime createdAt) {
    return aComment()
        .postId(postId)
        .authorId(REPLIER_ID)
        .parentId(parentId)
        .createdAt(createdAt)
        .insert(jdbc);
  }

  private CommentListQuery query(CommentCursor cursor, int size) {
    return new CommentListQuery(postId, cursor, size);
  }

  private static List<Long> idsOf(List<AuthoredComment> found) {
    return found.stream().map(authored -> authored.comment().getId()).toList();
  }
}
