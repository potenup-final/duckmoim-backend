package com.duckmoim.companion.service;

import static com.duckmoim.companion.CommentFixture.aComment;
import static com.duckmoim.companion.CompanionPostFixture.aCompanionPost;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.common.exception.ErrorCode;
import com.duckmoim.companion.domain.CommentStatus;
import com.duckmoim.companion.exception.CommentErrorCode;
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
 * 수정·삭제의 검증 기준 (CM-09 · CM-10).
 *
 * <p>판정 자체는 {@code CommentTest} 가 단위로 덮는다. 여기서는 <b>저장소를 지나야 성립하는 것</b>을 본다 — 방장이 누구인지를 모집글에서 읽어오는
 * 흐름, 그리고 저장값이 실제로 바뀌는지.
 *
 * <p><b>BLINDED 가 여기 있는 이유</b> — 블라인드로 가는 전이가 신고 처리(AD-07) 소관이라 아직 없다. 단위 테스트로는 그 상태를 만들 수 없어 SQL
 * 픽스처로 넣는다.
 *
 * <p>{@code CompanionPostFixture} 가 방장을 1 로 넣는다 (V11 시드의 방장덕후).
 */
@SpringBootTest
@Transactional
class CommentEditDeleteServiceTest {

  private static final long HOST_ID = 1L;
  private static final long AUTHOR_ID = 2L;
  private static final long STRANGER_ID = 4L;

  @Autowired private CommentCommandService commentCommandService;
  @Autowired private CommentRepository commentRepository;
  @Autowired private JdbcTemplate jdbc;

  private long postId;

  @BeforeEach
  void setUp() {
    postId = aCompanionPost().insert(jdbc);
  }

  @DisplayName("작성자가 본문을 고치면 저장값이 바뀐다.")
  @Test
  void edit() {
    long commentId = comment(CommentStatus.ACTIVE, false);

    commentCommandService.edit(new CommentEditCommand(commentId, AUTHOR_ID, "저 못 가게 됐어요", null));
    commentRepository.flush();

    assertThat(storedContent(commentId)).isEqualTo("저 못 가게 됐어요");
  }

  /** 방장은 남의 말을 지울 수는 있어도 고칠 수는 없다. */
  @DisplayName("방장도 남의 댓글을 고칠 수 없다.")
  @Test
  void edit_asHost() {
    long commentId = comment(CommentStatus.ACTIVE, false);

    assertThatThrownBy(
            () ->
                commentCommandService.edit(new CommentEditCommand(commentId, HOST_ID, "고친다", null)))
        .isInstanceOf(BusinessException.class)
        .extracting(CommentEditDeleteServiceTest::errorCodeOf)
        .isEqualTo(CommentErrorCode.COMMENT_NOT_AUTHOR);
  }

  @DisplayName("비밀 여부를 바꾸려 하면 고칠 수 없다.")
  @Test
  void edit_changesSecret() {
    long commentId = comment(CommentStatus.ACTIVE, false);

    assertThatThrownBy(
            () ->
                commentCommandService.edit(
                    new CommentEditCommand(commentId, AUTHOR_ID, "고친다", true)))
        .isInstanceOf(BusinessException.class)
        .extracting(CommentEditDeleteServiceTest::errorCodeOf)
        .isEqualTo(CommentErrorCode.COMMENT_SECRET_NOT_CHANGEABLE);
  }

  @DisplayName("작성자가 자기 댓글을 지운다.")
  @Test
  void delete() {
    long commentId = comment(CommentStatus.ACTIVE, false);

    commentCommandService.delete(commentId, AUTHOR_ID);
    commentRepository.flush();

    assertThat(storedStatus(commentId)).isEqualTo(CommentStatus.DELETED.name());
  }

  /** 방장이 누구인지는 모집글에 있다. 이 흐름이 저장소를 지나야 성립한다. */
  @DisplayName("방장이 남의 댓글을 지운다.")
  @Test
  void delete_asHost() {
    long commentId = comment(CommentStatus.ACTIVE, false);

    commentCommandService.delete(commentId, HOST_ID);
    commentRepository.flush();

    assertThat(storedStatus(commentId)).isEqualTo(CommentStatus.DELETED.name());
  }

  @DisplayName("작성자도 방장도 아니면 지울 수 없다.")
  @Test
  void delete_asStranger() {
    long commentId = comment(CommentStatus.ACTIVE, false);

    assertThatThrownBy(() -> commentCommandService.delete(commentId, STRANGER_ID))
        .isInstanceOf(BusinessException.class)
        .extracting(CommentEditDeleteServiceTest::errorCodeOf)
        .isEqualTo(CommentErrorCode.COMMENT_NOT_AUTHOR_OR_HOST);
  }

  @DisplayName("없는 댓글은 고치거나 지울 수 없다.")
  @Test
  void requireComment_isMissing() {
    assertThatThrownBy(() -> commentCommandService.delete(-1L, AUTHOR_ID))
        .isInstanceOf(BusinessException.class)
        .extracting(CommentEditDeleteServiceTest::errorCodeOf)
        .isEqualTo(CommentErrorCode.COMMENT_NOT_FOUND);
  }

  /** 자리표시자는 존재만 남은 것이지 조작 대상이 아니다. 도메인 6장에서 둘 다 종착이다. */
  @DisplayName("자리표시자는 지울 수 없다.")
  @ParameterizedTest(name = "{0}")
  @EnumSource(
      value = CommentStatus.class,
      names = {"DELETED", "BLINDED"})
  void delete_isNotActive(CommentStatus status) {
    long commentId = comment(status, false);

    assertThatThrownBy(() -> commentCommandService.delete(commentId, AUTHOR_ID))
        .isInstanceOf(BusinessException.class)
        .extracting(CommentEditDeleteServiceTest::errorCodeOf)
        .isEqualTo(CommentErrorCode.COMMENT_NOT_FOUND);
  }

  @DisplayName("자리표시자는 고칠 수 없다.")
  @ParameterizedTest(name = "{0}")
  @EnumSource(
      value = CommentStatus.class,
      names = {"DELETED", "BLINDED"})
  void edit_isNotActive(CommentStatus status) {
    long commentId = comment(status, false);

    assertThatThrownBy(
            () ->
                commentCommandService.edit(
                    new CommentEditCommand(commentId, AUTHOR_ID, "고친다", null)))
        .isInstanceOf(BusinessException.class)
        .extracting(CommentEditDeleteServiceTest::errorCodeOf)
        .isEqualTo(CommentErrorCode.COMMENT_NOT_FOUND);
  }

  private long comment(CommentStatus status, boolean secret) {
    return aComment().postId(postId).authorId(AUTHOR_ID).status(status).secret(secret).insert(jdbc);
  }

  private String storedContent(long commentId) {
    return jdbc.queryForObject("SELECT content FROM comment WHERE id = ?", String.class, commentId);
  }

  private String storedStatus(long commentId) {
    return jdbc.queryForObject("SELECT status FROM comment WHERE id = ?", String.class, commentId);
  }

  private static ErrorCode errorCodeOf(Throwable thrown) {
    return ((BusinessException) thrown).getErrorCode();
  }
}
