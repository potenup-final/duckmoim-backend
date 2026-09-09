package com.duckmoim.companion.service;

import static com.duckmoim.companion.CommentFixture.aComment;
import static com.duckmoim.companion.CompanionPostFixture.aCompanionPost;
import static org.assertj.core.api.Assertions.assertThat;

import com.duckmoim.companion.domain.CommentListQuery;
import com.duckmoim.companion.domain.CommentStatus;
import com.duckmoim.companion.infra.CommentRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>지우면 목록이 어떻게 되는가</b> (CM-11).
 *
 * <p>STAR-57 이 목록에서 거르는 규칙을 만들 때 {@code DELETED} 를 SQL 픽스처로 넣었다 — 삭제 경로가 아직 없어서였다. 이 티켓이 그 경로를
 * 만들었으므로 <b>실제로 지워서</b> 규칙이 도는지 본다. 픽스처가 넣은 상태와 코드가 만든 상태가 다르면 그때 드러난다.
 *
 * <p>검증 기준이 「대댓글이 고아가 되지 않음」이다. 지운 댓글이 사라지면서 그 아래 대댓글만 남는 일이 없어야 한다.
 */
@SpringBootTest
@Transactional
class CommentDeletionListTest {

  private static final LocalDateTime BASE = LocalDateTime.of(2026, 9, 1, 0, 0);

  private static final long HOST_ID = 1L;
  private static final long AUTHOR_ID = 2L;
  private static final long REPLIER_ID = 3L;

  @Autowired private CommentCommandService commentCommandService;
  @Autowired private CommentQueryService commentQueryService;
  @Autowired private CommentRepository commentRepository;
  @Autowired private JdbcTemplate jdbc;

  private long postId;

  @BeforeEach
  void setUp() {
    postId = aCompanionPost().insert(jdbc);
  }

  /** 대댓글이 고아가 되지 않아야 한다. */
  @DisplayName("하위 대댓글이 있는 댓글을 지우면 자리표시자로 목록에 남는다.")
  @Test
  void deleteRootWithReplies() {
    long rootId = root(BASE.plusMinutes(1));
    long replyId = reply(rootId, BASE.plusMinutes(2));

    commentCommandService.delete(rootId, AUTHOR_ID);
    commentRepository.flush();

    List<CommentView> roots = listRoots();
    assertThat(idsOf(roots)).containsExactly(rootId);
    assertThat(roots.get(0).comment().getStatus()).isEqualTo(CommentStatus.DELETED);
    assertThat(idsOf(roots.get(0).replies())).containsExactly(replyId);
  }

  @DisplayName("하위 대댓글이 없는 댓글을 지우면 목록에서 빠진다.")
  @Test
  void deleteBareRoot() {
    long rootId = root(BASE.plusMinutes(1));

    commentCommandService.delete(rootId, AUTHOR_ID);
    commentRepository.flush();

    assertThat(listRoots()).isEmpty();
  }

  @DisplayName("대댓글을 지우면 목록에서 빠지고 부모는 남는다.")
  @Test
  void deleteReply() {
    long rootId = root(BASE.plusMinutes(1));
    long replyId = reply(rootId, BASE.plusMinutes(2));

    commentCommandService.delete(replyId, REPLIER_ID);
    commentRepository.flush();

    List<CommentView> roots = listRoots();
    assertThat(idsOf(roots)).containsExactly(rootId);
    assertThat(roots.get(0).replies()).isEmpty();
  }

  /** 방장이 지워도 결과가 같다. 목록 규칙은 누가 지웠는지를 보지 않는다. */
  @DisplayName("방장이 지운 댓글도 같은 규칙으로 걸러진다.")
  @Test
  void deleteByHost() {
    long rootId = root(BASE.plusMinutes(1));

    commentCommandService.delete(rootId, HOST_ID);
    commentRepository.flush();

    assertThat(listRoots()).isEmpty();
  }

  /** 남은 대댓글이 없으면 아무것도 매달리지 않은 자리표시자만 뜬다. */
  @DisplayName("부모와 대댓글을 다 지우면 둘 다 목록에서 빠진다.")
  @Test
  void deleteRootAndReply() {
    long rootId = root(BASE.plusMinutes(1));
    long replyId = reply(rootId, BASE.plusMinutes(2));

    commentCommandService.delete(replyId, REPLIER_ID);
    commentCommandService.delete(rootId, AUTHOR_ID);
    commentRepository.flush();

    assertThat(listRoots()).isEmpty();
  }

  private List<CommentView> listRoots() {
    return commentQueryService.findComments(new CommentListQuery(postId, null, 20)).roots();
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

  private static List<Long> idsOf(List<CommentView> views) {
    return views.stream().map(view -> view.comment().getId()).toList();
  }
}
