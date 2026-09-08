package com.duckmoim.companion.service;

import static com.duckmoim.companion.CommentFixture.aComment;
import static com.duckmoim.companion.CompanionPostFixture.aCompanionPost;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.duckmoim.admin.domain.AuditKind;
import com.duckmoim.admin.domain.AuditLog;
import com.duckmoim.admin.domain.AuditLogListQuery;
import com.duckmoim.admin.domain.AuditTargetType;
import com.duckmoim.admin.infra.ActedAuditLog;
import com.duckmoim.admin.infra.AuditLogRepository;
import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.companion.domain.Comment;
import com.duckmoim.companion.domain.CommentStatus;
import com.duckmoim.companion.exception.CommentErrorCode;
import com.duckmoim.companion.infra.CommentRepository;
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
 * 관리자 블라인드와 그 기록 (AD-07 · AD-05).
 *
 * <p>기록을 흉내 내지 않고 실제 저장을 지난다. 완료 조건이 「`BLIND` 감사 로그가 남는다」라 <b>전이와 기록이 함께 일어나는지</b>가 검사 대상이고, 둘 중
 * 하나를 mock 으로 바꾸면 그 연결이 검증에서 빠진다.
 *
 * <p>행위자는 V11 시드의 6 번('운영자'). 작성자는 2 번('댓글덕후').
 *
 * <p>같은 트랜잭션인지는 여기서 볼 수 없다 — 이 클래스가 통째로 롤백된다. {@link AdminCommentBlindTransactionTest} 가 그것을 본다.
 */
@SpringBootTest
@Transactional
class AdminCommentCommandServiceTest {

  private static final long ADMIN_ID = 6L;
  private static final long AUTHOR_ID = 2L;

  @Autowired private AdminCommentCommandService adminCommentCommandService;
  @Autowired private CommentRepository commentRepository;
  @Autowired private AuditLogRepository auditLogRepository;
  @Autowired private JdbcTemplate jdbc;

  private long postId;

  @BeforeEach
  void setUp() {
    postId = aCompanionPost().insert(jdbc);
  }

  @DisplayName("관리자가 블라인드하면 상태가 BLINDED 가 된다.")
  @Test
  void blind() {
    long commentId = comment(CommentStatus.ACTIVE);

    adminCommentCommandService.blind(commentId, ADMIN_ID);

    assertThat(statusOf(commentId)).isEqualTo(CommentStatus.BLINDED);
  }

  /** 본문이 남아야 관리자가 CM-17 로 판단 재료를 보고, 가려진 뒤에도 신고를 계속 받는다 (STAR-60). */
  @DisplayName("가린 뒤에도 본문은 저장에 남는다.")
  @Test
  void blindKeepsContent() {
    long commentId = comment(CommentStatus.ACTIVE);

    adminCommentCommandService.blind(commentId, ADMIN_ID);

    assertThat(commentRepository.findById(commentId).orElseThrow().getContent()).isNotBlank();
  }

  @DisplayName("블라인드 한 번에 BLIND 감사 로그 한 줄이 남는다.")
  @Test
  void blindRecords() {
    long commentId = comment(CommentStatus.ACTIVE);

    adminCommentCommandService.blind(commentId, ADMIN_ID);

    AuditLog saved = onlyOne().auditLog();
    assertThat(saved.getActorUserId()).isEqualTo(ADMIN_ID);
    assertThat(saved.getKind()).isEqualTo(AuditKind.BLIND);
    assertThat(saved.getTargetType()).isEqualTo(AuditTargetType.COMMENT);
    assertThat(saved.getTargetId()).isEqualTo(commentId);
  }

  /** 도메인 6장에서 DELETED 와 BLINDED 는 각각 종착이고 둘 사이 전이가 없다. */
  @DisplayName("ACTIVE 가 아닌 댓글을 블라인드하면 409 다.")
  @ParameterizedTest(name = "{0}")
  @EnumSource(
      value = CommentStatus.class,
      names = {"DELETED", "BLINDED"})
  void blind_isNotActive(CommentStatus status) {
    long commentId = comment(status);

    assertThatThrownBy(() -> adminCommentCommandService.blind(commentId, ADMIN_ID))
        .isInstanceOf(BusinessException.class)
        .extracting(thrown -> ((BusinessException) thrown).getErrorCode())
        .isEqualTo(CommentErrorCode.COMMENT_NOT_ACTIVE);
  }

  /** 순서가 반대이면 일어나지 않은 조치가 장부에 오르고, 감사 로그는 고칠 수 없다 (I-13). */
  @DisplayName("금지된 전이에는 기록도 남지 않는다.")
  @Test
  void blind_isNotActiveRecordsNothing() {
    long commentId = comment(CommentStatus.BLINDED);

    assertThatThrownBy(() -> adminCommentCommandService.blind(commentId, ADMIN_ID))
        .isInstanceOf(BusinessException.class);

    assertThat(auditLogRepository.findSlice(new AuditLogListQuery(null, 20))).isEmpty();
  }

  @DisplayName("없는 댓글은 404 다.")
  @Test
  void blind_isMissing() {
    assertThatThrownBy(() -> adminCommentCommandService.blind(-1L, ADMIN_ID))
        .isInstanceOf(BusinessException.class)
        .extracting(thrown -> ((BusinessException) thrown).getErrorCode())
        .isEqualTo(CommentErrorCode.COMMENT_NOT_FOUND);
  }

  /** 여기서 대댓글까지 손대면 CM-11 의 자리표시자 규칙이 무의미해지고 대댓글이 고아가 된다. */
  @DisplayName("하위 대댓글의 상태는 건드리지 않는다.")
  @Test
  void blindLeavesRepliesAlone() {
    long rootId = comment(CommentStatus.ACTIVE);
    long replyId = aComment().postId(postId).authorId(AUTHOR_ID).parentId(rootId).insert(jdbc);

    adminCommentCommandService.blind(rootId, ADMIN_ID);

    assertThat(statusOf(replyId)).isEqualTo(CommentStatus.ACTIVE);
  }

  private long comment(CommentStatus status) {
    return aComment().postId(postId).authorId(AUTHOR_ID).status(status).insert(jdbc);
  }

  private CommentStatus statusOf(long commentId) {
    Comment found = commentRepository.findById(commentId).orElseThrow();
    return found.getStatus();
  }

  private ActedAuditLog onlyOne() {
    List<ActedAuditLog> found = auditLogRepository.findSlice(new AuditLogListQuery(null, 20));

    assertThat(found).hasSize(1);
    return found.get(0);
  }
}
