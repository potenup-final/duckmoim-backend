package com.duckmoim.companion.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.companion.exception.CommentErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 댓글 작성의 도메인 규칙 (CM-01 · CM-02 · CM-03).
 *
 * <p><b>깊이 초과(I-06)는 여기 없다.</b> 대댓글이려면 parentId 가 있어야 하고 그 값은 부모가 저장된 뒤에 생기는 id 다. 저장 없이 대댓글을 만들려면
 * 프로덕션이 쓰지 않는 생성 경로를 도메인에 뚫어야 하는데, EventFixture 가 같은 이유로 그것을 하지 않기로 했다. 규칙은 {@link Comment#reply}
 * 안에 있고 판정은 서비스 통합 테스트가 실제 id 로 본다.
 */
class CommentTest {

  private static final long POST_ID = 1L;
  private static final long AUTHOR_ID = 2L;
  private static final long HOST_ID = 3L;
  private static final long STRANGER_ID = 4L;

  @DisplayName("루트 댓글을 작성하면 부모가 없고 활성 상태다.")
  @Test
  void root() {
    Comment comment = Comment.root(POST_ID, AUTHOR_ID, "저 갈게요!", false);

    assertThat(comment.getParentId()).isNull();
    assertThat(comment.isReply()).isFalse();
    assertThat(comment.getStatus()).isEqualTo(CommentStatus.ACTIVE);
    assertThat(comment.isActive()).isTrue();
  }

  @DisplayName("비밀 여부는 작성할 때 정한 값이 그대로 남는다.")
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void rootKeepsSecret(boolean secret) {
    Comment comment = Comment.root(POST_ID, AUTHOR_ID, "연락처 남길게요", secret);

    assertThat(comment.isSecret()).isEqualTo(secret);
  }

  @DisplayName("대댓글은 부모가 달린 모집글에 함께 달린다.")
  @Test
  void reply() {
    Comment parent = Comment.root(POST_ID, AUTHOR_ID, "저 갈게요!", false);

    Comment reply = parent.reply(3L, "저도요", false);

    assertThat(reply.getPostId()).isEqualTo(POST_ID);
    assertThat(reply.getAuthorId()).isEqualTo(3L);
    assertThat(reply.getStatus()).isEqualTo(CommentStatus.ACTIVE);
  }

  @DisplayName("다른 모집글의 댓글인지 판정한다.")
  @Test
  void belongsTo() {
    Comment comment = Comment.root(POST_ID, AUTHOR_ID, "저 갈게요!", false);

    assertThat(comment.belongsTo(POST_ID)).isTrue();
    assertThat(comment.belongsTo(99L)).isFalse();
  }

  @DisplayName("작성자가 본문을 고친다.")
  @Test
  void edit() {
    Comment comment = comment(false);

    comment.edit(AUTHOR_ID, "저 못 가게 됐어요", null);

    assertThat(comment.getContent()).isEqualTo("저 못 가게 됐어요");
  }

  @DisplayName("작성자가 아니면 고칠 수 없다.")
  @ParameterizedTest(name = "요청자 {0}")
  @ValueSource(longs = {HOST_ID, STRANGER_ID})
  void edit_isNotAuthor(long requesterId) {
    Comment comment = comment(false);

    assertThatThrownBy(() -> comment.edit(requesterId, "고친다", null))
        .isInstanceOf(BusinessException.class)
        .extracting(thrown -> ((BusinessException) thrown).getErrorCode())
        .isEqualTo(CommentErrorCode.COMMENT_NOT_AUTHOR);
  }

  /** 이미 읽은 사람을 되돌릴 수 없고, 반대로 열면 연락처가 전체에 열린다. */
  @DisplayName("비밀 여부는 바꿀 수 없다.")
  @ParameterizedTest(name = "{0} → {1}")
  @CsvSource({"false, true", "true, false"})
  void edit_changesSecret(boolean stored, boolean requested) {
    Comment comment = comment(stored);

    assertThatThrownBy(() -> comment.edit(AUTHOR_ID, "고친다", requested))
        .isInstanceOf(BusinessException.class)
        .extracting(thrown -> ((BusinessException) thrown).getErrorCode())
        .isEqualTo(CommentErrorCode.COMMENT_SECRET_NOT_CHANGEABLE);
  }

  /** 프론트가 폼 전체를 되보내는 것을 막지 않는다. */
  @DisplayName("비밀 여부를 같은 값으로 보내면 고칠 수 있다.")
  @ParameterizedTest(name = "secret={0}")
  @ValueSource(booleans = {true, false})
  void edit_keepsSecret(boolean secret) {
    Comment comment = comment(secret);

    comment.edit(AUTHOR_ID, "고친다", secret);

    assertThat(comment.getContent()).isEqualTo("고친다");
    assertThat(comment.isSecret()).isEqualTo(secret);
  }

  @DisplayName("작성자가 자기 댓글을 지운다.")
  @Test
  void deleteByAuthor() {
    Comment comment = comment(false);

    comment.deleteBy(AUTHOR_ID, HOST_ID);

    assertThat(comment.getStatus()).isEqualTo(CommentStatus.DELETED);
    assertThat(comment.isActive()).isFalse();
  }

  /** 자기 모집글에 달린 글을 관리해야 한다. 수정은 못 하고 삭제만 할 수 있다. */
  @DisplayName("방장이 남의 댓글을 지운다.")
  @Test
  void deleteByHost() {
    Comment comment = comment(false);

    comment.deleteBy(HOST_ID, HOST_ID);

    assertThat(comment.getStatus()).isEqualTo(CommentStatus.DELETED);
  }

  @DisplayName("작성자도 방장도 아니면 지울 수 없다.")
  @Test
  void deleteBy_isNeitherAuthorNorHost() {
    Comment comment = comment(false);

    assertThatThrownBy(() -> comment.deleteBy(STRANGER_ID, HOST_ID))
        .isInstanceOf(BusinessException.class)
        .extracting(thrown -> ((BusinessException) thrown).getErrorCode())
        .isEqualTo(CommentErrorCode.COMMENT_NOT_AUTHOR_OR_HOST);
  }

  /**
   * 금지된 전이다. 도메인 6장에서 DELETED 가 종착이고 되돌아오는 전이가 없다.
   *
   * <p>BLINDED 에서 출발하는 경우는 여기서 못 만든다 — 블라인드로 가는 전이는 신고 처리(AD-07) 소관이라 아직 없다. 같은 가드를 지나므로 서비스 통합
   * 테스트가 픽스처로 확인한다.
   */
  @DisplayName("이미 지운 댓글은 다시 지울 수 없다.")
  @Test
  void deleteBy_isAlreadyDeleted() {
    Comment comment = comment(false);
    comment.deleteBy(AUTHOR_ID, HOST_ID);

    assertThatThrownBy(() -> comment.deleteBy(AUTHOR_ID, HOST_ID))
        .isInstanceOf(BusinessException.class)
        .extracting(thrown -> ((BusinessException) thrown).getErrorCode())
        .isEqualTo(CommentErrorCode.COMMENT_NOT_FOUND);
  }

  @DisplayName("이미 지운 댓글은 고칠 수 없다.")
  @Test
  void edit_isAlreadyDeleted() {
    Comment comment = comment(false);
    comment.deleteBy(AUTHOR_ID, HOST_ID);

    assertThatThrownBy(() -> comment.edit(AUTHOR_ID, "고친다", null))
        .isInstanceOf(BusinessException.class)
        .extracting(thrown -> ((BusinessException) thrown).getErrorCode())
        .isEqualTo(CommentErrorCode.COMMENT_NOT_FOUND);
  }

  private static Comment comment(boolean secret) {
    return Comment.root(POST_ID, AUTHOR_ID, "저 갈게요!", secret);
  }
}
