package com.duckmoim.companion.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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
}
