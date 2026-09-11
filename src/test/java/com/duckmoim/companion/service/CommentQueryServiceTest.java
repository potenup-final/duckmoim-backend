package com.duckmoim.companion.service;

import static com.duckmoim.companion.CommentFixture.aComment;
import static com.duckmoim.companion.CompanionPostFixture.aCompanionPost;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.companion.domain.CommentListQuery;
import com.duckmoim.companion.domain.CommentStatus;
import com.duckmoim.companion.exception.PostErrorCode;
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
 * 목록에 무엇이 실리는지 (CM-06 · CM-07 · CM-11).
 *
 * <p>정렬과 커서 경계는 {@code CommentQueryRepositoryTest} 가 본다. 여기서는 <b>읽은 것을 어떻게 묶고 무엇을 빼는지</b>를 본다.
 *
 * <p>작성자는 V11 시드를 쓴다 — 2 부모댓글작성자 · 3 대댓글작성자.
 */
@SpringBootTest
@Transactional
class CommentQueryServiceTest {

  private static final LocalDateTime BASE = LocalDateTime.of(2026, 9, 1, 0, 0);

  private static final long AUTHOR_ID = 2L;
  private static final long REPLIER_ID = 3L;

  @Autowired private CommentQueryService commentQueryService;
  @Autowired private JdbcTemplate jdbc;

  private long postId;

  @BeforeEach
  void setUp() {
    postId = aCompanionPost().insert(jdbc);
  }

  @DisplayName("대댓글이 부모 아래에 시간순으로 달린다.")
  @Test
  void findComments() {
    long rootId = root(BASE.plusMinutes(1), CommentStatus.ACTIVE);
    long laterReply = reply(rootId, BASE.plusMinutes(9), CommentStatus.ACTIVE);
    long earlierReply = reply(rootId, BASE.plusMinutes(4), CommentStatus.ACTIVE);

    CommentSlice slice = commentQueryService.findComments(query(20));

    assertThat(slice.roots()).hasSize(1);
    assertThat(idsOf(slice.roots().get(0).replies())).containsExactly(earlierReply, laterReply);
  }

  @DisplayName("작성자의 최근 접속이 구간으로 실린다.")
  @Test
  void findComments_carriesLastSeen() {
    root(BASE, CommentStatus.ACTIVE);

    CommentView view = commentQueryService.findComments(query(20)).roots().get(0);

    assertThat(view.nickname()).isEqualTo("댓글덕후");
    assertThat(view.lastSeen()).isNotNull();
  }

  @DisplayName("마지막 페이지에는 다음 커서가 없다.")
  @Test
  void findComments_atLastPage() {
    root(BASE.plusMinutes(1), CommentStatus.ACTIVE);

    CommentSlice slice = commentQueryService.findComments(query(20));

    assertThat(slice.hasNext()).isFalse();
    assertThat(slice.nextCursor()).isNull();
  }

  @DisplayName("다음 페이지가 있으면 마지막 루트를 가리키는 커서를 준다.")
  @Test
  void findComments_hasNextPage() {
    root(BASE.plusMinutes(1), CommentStatus.ACTIVE);
    long second = root(BASE.plusMinutes(2), CommentStatus.ACTIVE);
    root(BASE.plusMinutes(3), CommentStatus.ACTIVE);

    CommentSlice slice = commentQueryService.findComments(query(2));

    assertThat(slice.hasNext()).isTrue();
    assertThat(slice.nextCursor().id()).isEqualTo(second);
  }

  /** CM-11 — 대댓글이 고아가 되지 않아야 한다. */
  @DisplayName("하위 대댓글이 있는 자리표시자는 목록에 남는다.")
  @ParameterizedTest(name = "{0}")
  @EnumSource(
      value = CommentStatus.class,
      names = {"DELETED", "BLINDED"})
  void findComments_keepsPlaceholderWithReplies(CommentStatus status) {
    long rootId = root(BASE.plusMinutes(1), status);
    long replyId = reply(rootId, BASE.plusMinutes(2), CommentStatus.ACTIVE);

    CommentSlice slice = commentQueryService.findComments(query(20));

    assertThat(idsOf(slice.roots())).containsExactly(rootId);
    assertThat(idsOf(slice.roots().get(0).replies())).containsExactly(replyId);
  }

  @DisplayName("하위 대댓글이 없는 자리표시자는 목록에서 빠진다.")
  @ParameterizedTest(name = "{0}")
  @EnumSource(
      value = CommentStatus.class,
      names = {"DELETED", "BLINDED"})
  void findComments_dropsBarePlaceholder(CommentStatus status) {
    root(BASE.plusMinutes(1), status);

    assertThat(commentQueryService.findComments(query(20)).roots()).isEmpty();
  }

  /** 대댓글은 자기 대댓글을 가질 수 없어(I-06) 지워지면 언제나 빠진다. */
  @DisplayName("지워진 대댓글은 목록에서 빠진다.")
  @Test
  void findComments_dropsDeletedReply() {
    long rootId = root(BASE.plusMinutes(1), CommentStatus.ACTIVE);
    reply(rootId, BASE.plusMinutes(2), CommentStatus.DELETED);

    CommentSlice slice = commentQueryService.findComments(query(20));

    assertThat(idsOf(slice.roots())).containsExactly(rootId);
    assertThat(slice.roots().get(0).replies()).isEmpty();
  }

  /** 남은 대댓글이 없으면 아무것도 매달리지 않은 자리표시자만 뜬다. */
  @DisplayName("대댓글이 모두 지워지면 자리표시자 루트도 빠진다.")
  @Test
  void findComments_dropsPlaceholderWhenRepliesAlsoGone() {
    long rootId = root(BASE.plusMinutes(1), CommentStatus.DELETED);
    reply(rootId, BASE.plusMinutes(2), CommentStatus.DELETED);

    assertThat(commentQueryService.findComments(query(20)).roots()).isEmpty();
  }

  /** 걸러진 뒤를 기준으로 삼으면 그 댓글을 다음 페이지가 다시 읽어 같은 자리를 맴돈다. */
  @DisplayName("목록에서 빠진 댓글도 커서 위치로는 센다.")
  @Test
  void findComments_countsDroppedForCursor() {
    root(BASE.plusMinutes(1), CommentStatus.ACTIVE);
    long dropped = root(BASE.plusMinutes(2), CommentStatus.DELETED);
    root(BASE.plusMinutes(3), CommentStatus.ACTIVE);

    CommentSlice slice = commentQueryService.findComments(query(2));

    assertThat(slice.roots()).hasSize(1);
    assertThat(slice.hasNext()).isTrue();
    assertThat(slice.nextCursor().id()).isEqualTo(dropped);
  }

  /** 닉네임이 {@code NULL} 이 될 뿐이면 목록에 이름 없는 작성자로 뜬다 (AU-11 「닉네임 익명화」). */
  @DisplayName("탈퇴한 작성자의 닉네임은 자리표시자로 나온다.")
  @Test
  void findComments_anonymizesWithdrawnAuthor() {
    withdraw(AUTHOR_ID);
    root(BASE, CommentStatus.ACTIVE);

    CommentView view = commentQueryService.findComments(query(20)).roots().get(0);

    assertThat(view.nickname()).isEqualTo("탈퇴한 회원");
  }

  /** 작성 댓글은 자리표시자로 남는다 (AU-11). 지우면 매달린 대댓글이 고아가 되는 것과 같은 이유다 (CM-11). */
  @DisplayName("탈퇴한 작성자의 댓글도 목록에는 남는다.")
  @Test
  void findComments_keepsWithdrawnAuthorComment() {
    withdraw(AUTHOR_ID);
    long rootId = root(BASE, CommentStatus.ACTIVE);

    CommentSlice slice = commentQueryService.findComments(query(20));

    assertThat(idsOf(slice.roots())).containsExactly(rootId);
  }

  /** 대댓글 작성자만 탈퇴한 경우다. 익명화를 루트에만 걸면 여기서 실명이 샌다. */
  @DisplayName("탈퇴한 대댓글 작성자도 자리표시자로 나온다.")
  @Test
  void findComments_anonymizesWithdrawnReplier() {
    withdraw(REPLIER_ID);
    long rootId = root(BASE, CommentStatus.ACTIVE);
    reply(rootId, BASE.plusMinutes(1), CommentStatus.ACTIVE);

    CommentView root = commentQueryService.findComments(query(20)).roots().get(0);

    assertThat(root.nickname()).isEqualTo("댓글덕후");
    assertThat(root.replies().get(0).nickname()).isEqualTo("탈퇴한 회원");
  }

  @DisplayName("없는 모집글의 댓글은 조회할 수 없다.")
  @Test
  void findComments_postIsMissing() {
    assertThatThrownBy(() -> commentQueryService.findComments(new CommentListQuery(-1L, null, 20)))
        .isInstanceOf(BusinessException.class)
        .extracting(thrown -> ((BusinessException) thrown).getErrorCode())
        .isEqualTo(PostErrorCode.POST_NOT_FOUND);
  }

  private long root(LocalDateTime createdAt, CommentStatus status) {
    return aComment()
        .postId(postId)
        .authorId(AUTHOR_ID)
        .createdAt(createdAt)
        .status(status)
        .insert(jdbc);
  }

  private long reply(long parentId, LocalDateTime createdAt, CommentStatus status) {
    return aComment()
        .postId(postId)
        .authorId(REPLIER_ID)
        .parentId(parentId)
        .createdAt(createdAt)
        .status(status)
        .insert(jdbc);
  }

  /** {@code User.withdraw} 가 남기는 모양 그대로다 — 상태와 시각을 찍고 닉네임 · 사진을 비운다 (AU-11). */
  private void withdraw(long userId) {
    jdbc.update(
        """
        UPDATE user
           SET status = 'WITHDRAWN', withdrawn_at = ?, nickname = NULL, profile_image_url = NULL
         WHERE id = ?
        """,
        BASE,
        userId);
  }

  private CommentListQuery query(int size) {
    return new CommentListQuery(postId, null, size);
  }

  private static List<Long> idsOf(List<CommentView> views) {
    return views.stream().map(view -> view.comment().getId()).toList();
  }
}
