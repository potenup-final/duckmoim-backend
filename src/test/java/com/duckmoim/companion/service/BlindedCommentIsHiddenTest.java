package com.duckmoim.companion.service;

import static com.duckmoim.companion.CommentFixture.aComment;
import static com.duckmoim.companion.CompanionPostFixture.aCompanionPost;
import static org.assertj.core.api.Assertions.assertThat;

import com.duckmoim.companion.domain.CommentListQuery;
import com.duckmoim.companion.domain.CommentReadContext;
import com.duckmoim.companion.domain.CommentReadTarget;
import com.duckmoim.companion.domain.CommentStatus;
import com.duckmoim.companion.domain.CommentVisibilityPolicy;
import com.duckmoim.companion.infra.CommentRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 블라인드가 조회에 실제로 닿는지 (AD-07 · CM-05 · CM-11 · CM-12).
 *
 * <p><b>기존 검사와 무엇이 다른가.</b> 조회 쪽 규칙은 이미 {@code BLINDED} 를 이름으로 지목해 덮여 있다 — {@code
 * CommentVisibilityPolicyTest} · {@code CommentActionPolicyTest} · {@code CommentQueryServiceTest}
 * · {@code CommentItemAssemblerTest} · {@code MyCommentQueryRepositoryTest} · {@code
 * CompanionPostQueryRepositoryTest} 가 그렇다. 다만 <b>전부 SQL 픽스처로 상태를 넣고 시작한다.</b>
 *
 * <p>그래서 「가려진 댓글이 어떻게 보이는가」는 증명돼 있어도 <b>「가리면 그렇게 되는가」는 어디에도 없다.</b> AD-07 의 검증 기준이 후자다 — <i>"블라인드 후
 * 본문 미노출, 대댓글 고아화 없음"</i>. 이 클래스만 {@link AdminCommentCommandService#blind} 를 실제로 지나 그 이음매를 본다.
 *
 * <p>행위자는 V11 시드의 6 번('운영자'). 작성자는 2 번('댓글덕후'), 방장은 픽스처가 1 로 넣는다.
 */
@SpringBootTest
@Transactional
class BlindedCommentIsHiddenTest {

  private static final long ADMIN_ID = 6L;
  private static final long AUTHOR_ID = 2L;
  private static final long HOST_ID = 1L;

  private final CommentVisibilityPolicy visibilityPolicy = new CommentVisibilityPolicy();

  @Autowired private AdminCommentCommandService adminCommentCommandService;
  @Autowired private CommentQueryService commentQueryService;
  @Autowired private CommentRepository commentRepository;
  @Autowired private JdbcTemplate jdbc;

  private long postId;

  @BeforeEach
  void setUp() {
    postId = aCompanionPost().insert(jdbc);
  }

  /** 작성자 본인도 못 본다. 7.1 이 삭제·블라인드를 「없음 (자리표시자만)」으로 정했다. */
  @DisplayName("가린 뒤에는 작성자에게도 본문을 보여주지 않는다.")
  @Test
  void blindHidesContentFromAuthor() {
    long commentId = comment(null);

    adminCommentCommandService.blind(commentId, ADMIN_ID);

    assertThat(canRead(commentId, AUTHOR_ID)).isFalse();
  }

  @DisplayName("가린 뒤에는 방장에게도 본문을 보여주지 않는다.")
  @Test
  void blindHidesContentFromHost() {
    long commentId = comment(null);

    adminCommentCommandService.blind(commentId, ADMIN_ID);

    assertThat(canRead(commentId, HOST_ID)).isFalse();
  }

  /** CM-11 의 검증 기준이 걸린 자리다 — 「대댓글 고아화 없음」. */
  @DisplayName("하위 대댓글이 있으면 가린 뒤에도 목록에 자리표시자로 남는다.")
  @Test
  void blindKeepsPlaceholderWhenRepliesRemain() {
    long rootId = comment(null);
    long replyId = comment(rootId);

    adminCommentCommandService.blind(rootId, ADMIN_ID);

    List<CommentView> roots = roots();
    assertThat(roots).hasSize(1);
    assertThat(roots.get(0).comment().getId()).isEqualTo(rootId);
    assertThat(roots.get(0).comment().getStatus()).isEqualTo(CommentStatus.BLINDED);
    assertThat(roots.get(0).replies())
        .extracting(view -> view.comment().getId())
        .containsExactly(replyId);
  }

  @DisplayName("하위 대댓글이 없으면 가린 뒤 목록에서 빠진다.")
  @Test
  void blindDropsCommentWhenNoReplies() {
    long commentId = comment(null);

    adminCommentCommandService.blind(commentId, ADMIN_ID);

    assertThat(roots()).isEmpty();
  }

  /** CM-12 — 비밀 포함, 삭제·블라인드 제외. */
  @DisplayName("가린 댓글은 댓글 수 집계에서 빠진다.")
  @Test
  void blindDropsCommentFromCount() {
    long commentId = comment(null);
    comment(null);

    assertThat(countOfPost()).isEqualTo(2);

    adminCommentCommandService.blind(commentId, ADMIN_ID);

    assertThat(countOfPost()).isEqualTo(1);
  }

  private long comment(Long parentId) {
    return aComment().postId(postId).authorId(AUTHOR_ID).parentId(parentId).insert(jdbc);
  }

  private boolean canRead(long commentId, long requesterId) {
    var comment = commentRepository.findById(commentId).orElseThrow();

    return visibilityPolicy.canReadContent(
        new CommentReadTarget(comment.getAuthorId(), comment.isSecret(), comment.getStatus()),
        new CommentReadContext(requesterId, HOST_ID, null));
  }

  private List<CommentView> roots() {
    return commentQueryService
        .findComments(new CommentListQuery(postId, null, CommentListQuery.DEFAULT_SIZE))
        .roots();
  }

  private long countOfPost() {
    return commentRepository.countActiveByPostIds(List.of(postId)).getOrDefault(postId, 0L);
  }
}
