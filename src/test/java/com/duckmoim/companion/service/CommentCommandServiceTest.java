package com.duckmoim.companion.service;

import static com.duckmoim.companion.CommentFixture.aComment;
import static com.duckmoim.companion.CompanionPostFixture.aCompanionPost;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.companion.domain.CommentStatus;
import com.duckmoim.companion.domain.PostStatus;
import com.duckmoim.companion.exception.CommentErrorCode;
import com.duckmoim.companion.exception.PostErrorCode;
import com.duckmoim.companion.infra.CommentRepository;
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
 * 댓글 작성의 검증 기준 (CM-01 · CM-02 · CM-03).
 *
 * <p>실제 MySQL 로 돈다. 부모 댓글과 모집글을 저장소에서 읽어야 판정되는 규칙이라 mock 으로는 검증되지 않고, 테스트 컨벤션이 H2 도 금지했다.
 *
 * <p><b>깊이 초과(I-06)가 여기 있는 이유.</b> 「이미 대댓글인 댓글」은 부모의 id 가 있어야 만들어지고 그 id 는 저장 뒤에 생긴다. 규칙 자체는 {@code
 * Comment.reply} 안에 있고, 여기서는 실제 id 를 가진 부모로 그 규칙이 도는지를 본다.
 *
 * <p>동시성 테스트가 없다. 작성 경로에 유니크 제약도 증가시킬 카운트도 없고 (댓글 수를 저장하지 않는다), 마감 찰나의 한 건은 <b>막지 않기로 한 것</b>이라
 * 테스트로 굳히면 결정과 반대가 된다.
 */
@SpringBootTest
@Transactional
class CommentCommandServiceTest {

  private static final long AUTHOR_ID = 7L;
  private static final long OTHER_AUTHOR_ID = 8L;

  @Autowired private CommentCommandService commentCommandService;
  @Autowired private CommentRepository commentRepository;
  @Autowired private JdbcTemplate jdbc;

  private long openPostId;

  @BeforeEach
  void setUp() {
    openPostId = aCompanionPost().insert(jdbc);
  }

  @DisplayName("열린 모집글에 댓글을 작성한다.")
  @Test
  void write() {
    WrittenComment written = commentCommandService.write(rootCommand(openPostId, "저 갈게요!"));
    commentRepository.flush();

    assertThat(written.id()).isNotNull();
    assertThat(written.parentId()).isNull();
    assertThat(written.status()).isEqualTo(CommentStatus.ACTIVE);
    assertThat(storedColumn(written.id(), "post_id")).isEqualTo(openPostId);
    assertThat(storedColumn(written.id(), "author_id")).isEqualTo(AUTHOR_ID);
  }

  @DisplayName("마감된 모집글에는 댓글을 작성할 수 없다.")
  @Test
  void writeToClosedPost() {
    long closedPostId = aCompanionPost().status(PostStatus.CLOSED).insert(jdbc);

    assertThatThrownBy(() -> commentCommandService.write(rootCommand(closedPostId, "저 갈게요!")))
        .isInstanceOf(BusinessException.class)
        .extracting(thrown -> ((BusinessException) thrown).getErrorCode())
        .isEqualTo(PostErrorCode.POST_ALREADY_CLOSED);
  }

  @DisplayName("없는 모집글에는 댓글을 작성할 수 없다.")
  @Test
  void writeToMissingPost() {
    assertThatThrownBy(() -> commentCommandService.write(rootCommand(-1L, "저 갈게요!")))
        .isInstanceOf(BusinessException.class)
        .extracting(thrown -> ((BusinessException) thrown).getErrorCode())
        .isEqualTo(PostErrorCode.POST_NOT_FOUND);
  }

  @DisplayName("루트 댓글에 대댓글을 작성한다.")
  @Test
  void writeReply() {
    long parentId = aComment().postId(openPostId).insert(jdbc);

    WrittenComment written = commentCommandService.write(replyCommand(openPostId, parentId, "저도요"));
    commentRepository.flush();

    assertThat(written.parentId()).isEqualTo(parentId);
    assertThat(storedColumn(written.id(), "post_id")).isEqualTo(openPostId);
  }

  @DisplayName("대댓글에는 대댓글을 작성할 수 없다.")
  @Test
  void writeReplyToReply() {
    long rootId = aComment().postId(openPostId).insert(jdbc);
    long replyId = aComment().postId(openPostId).parentId(rootId).insert(jdbc);

    assertThatThrownBy(() -> commentCommandService.write(replyCommand(openPostId, replyId, "저도요")))
        .isInstanceOf(BusinessException.class)
        .extracting(thrown -> ((BusinessException) thrown).getErrorCode())
        .isEqualTo(CommentErrorCode.COMMENT_DEPTH_EXCEEDED);
  }

  @DisplayName("없는 부모 댓글로는 대댓글을 작성할 수 없다.")
  @Test
  void writeReplyToMissingParent() {
    assertThatThrownBy(() -> commentCommandService.write(replyCommand(openPostId, -1L, "저도요")))
        .isInstanceOf(BusinessException.class)
        .extracting(thrown -> ((BusinessException) thrown).getErrorCode())
        .isEqualTo(CommentErrorCode.COMMENT_NOT_FOUND);
  }

  @DisplayName("다른 모집글의 댓글을 부모로 지정할 수 없다.")
  @Test
  void writeReplyToParentOfAnotherPost() {
    long anotherPostId = aCompanionPost().insert(jdbc);
    long parentId = aComment().postId(anotherPostId).insert(jdbc);

    assertThatThrownBy(() -> commentCommandService.write(replyCommand(openPostId, parentId, "저도요")))
        .isInstanceOf(BusinessException.class)
        .extracting(thrown -> ((BusinessException) thrown).getErrorCode())
        .isEqualTo(CommentErrorCode.COMMENT_NOT_FOUND);
  }

  @DisplayName("자리표시자로만 남은 댓글에는 대댓글을 작성할 수 없다.")
  @ParameterizedTest
  @EnumSource(
      value = CommentStatus.class,
      names = {"DELETED", "BLINDED"})
  void writeReplyToInactiveParent(CommentStatus status) {
    long parentId = aComment().postId(openPostId).status(status).insert(jdbc);

    assertThatThrownBy(() -> commentCommandService.write(replyCommand(openPostId, parentId, "저도요")))
        .isInstanceOf(BusinessException.class)
        .extracting(thrown -> ((BusinessException) thrown).getErrorCode())
        .isEqualTo(CommentErrorCode.COMMENT_NOT_FOUND);
  }

  @DisplayName("비밀 댓글로 작성하면 비밀 여부가 저장된다.")
  @Test
  void writeSecret() {
    WrittenComment written =
        commentCommandService.write(
            new CommentWriteCommand(openPostId, AUTHOR_ID, null, "연락처 남길게요", true));
    commentRepository.flush();

    assertThat(storedSecret(written.id())).isTrue();
  }

  @DisplayName("대댓글도 비밀로 작성할 수 있다.")
  @Test
  void writeSecretReply() {
    long parentId = aComment().postId(openPostId).insert(jdbc);

    WrittenComment written =
        commentCommandService.write(
            new CommentWriteCommand(openPostId, OTHER_AUTHOR_ID, parentId, "저도 연락처 남길게요", true));
    commentRepository.flush();

    assertThat(storedSecret(written.id())).isTrue();
    assertThat(written.parentId()).isEqualTo(parentId);
  }

  /** 결과 객체에 없는 컬럼을 저장값에서 읽는다. postId · authorId 는 응답에 나가지 않아 여기서만 확인된다. */
  private Long storedColumn(long commentId, String column) {
    return jdbc.queryForObject(
        "SELECT " + column + " FROM comment WHERE id = ?", Long.class, commentId);
  }

  /** 저장값을 SQL 로 다시 읽는다. 영속성 컨텍스트에 남은 객체를 보면 「저장됐는지」를 검증하지 못한다. */
  private boolean storedSecret(long commentId) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject("SELECT secret FROM comment WHERE id = ?", Boolean.class, commentId));
  }

  private CommentWriteCommand rootCommand(long postId, String content) {
    return new CommentWriteCommand(postId, AUTHOR_ID, null, content, false);
  }

  private CommentWriteCommand replyCommand(long postId, long parentId, String content) {
    return new CommentWriteCommand(postId, OTHER_AUTHOR_ID, parentId, content, false);
  }
}
