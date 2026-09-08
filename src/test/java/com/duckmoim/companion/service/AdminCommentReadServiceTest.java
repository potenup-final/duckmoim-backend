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
import com.duckmoim.companion.domain.CommentStatus;
import com.duckmoim.companion.exception.CommentErrorCode;
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
 * 관리자 열람과 그 기록 (CM-17 · AD-05).
 *
 * <p>기록을 흉내 내지 않고 실제 저장을 지난다. 이 티켓의 검증 기준이 「열람할 때마다 감사 로그 기록」이라 <b>열람과 기록이 함께 일어나는지</b>가 검사 대상이고, 둘
 * 중 하나를 mock 으로 바꾸면 그 연결이 검증에서 빠진다.
 *
 * <p>행위자는 V11 시드의 6 번('운영자')이다. 작성자는 2 번('댓글덕후').
 *
 * <p>같은 트랜잭션인지는 여기서 볼 수 없다. 이 클래스가 통째로 롤백되기 때문이다 — {@link AdminCommentReadTransactionTest} 가 그것을
 * 본다.
 */
@SpringBootTest
@Transactional
class AdminCommentReadServiceTest {

  private static final long ADMIN_ID = 6L;
  private static final long AUTHOR_ID = 2L;

  @Autowired private AdminCommentReadService adminCommentReadService;
  @Autowired private AuditLogRepository auditLogRepository;
  @Autowired private JdbcTemplate jdbc;

  private long postId;

  @BeforeEach
  void setUp() {
    postId = aCompanionPost().insert(jdbc);
  }

  /** 이 경로가 있는 이유 그 자체다. 관리자는 가시성 매트릭스 밖이라 secret 이 본문을 가리지 않는다 (도메인 7.1). */
  @DisplayName("관리자는 비밀 댓글 본문을 얻는다.")
  @Test
  void readSecret() {
    long commentId = comment(true, CommentStatus.ACTIVE);

    AdminCommentView view = adminCommentReadService.read(commentId, ADMIN_ID, null);

    assertThat(view.comment().isSecret()).isTrue();
    assertThat(view.comment().getContent()).isNotBlank();
    assertThat(view.nickname()).isEqualTo("댓글덕후");
  }

  @DisplayName("관리자는 공개 댓글도 같은 경로로 본문을 얻는다.")
  @Test
  void readPublic() {
    long commentId = comment(false, CommentStatus.ACTIVE);

    assertThat(adminCommentReadService.read(commentId, ADMIN_ID, null).comment().getContent())
        .isNotBlank();
  }

  /** 지운 댓글을 못 읽으면 신고당한 사람이 지우는 것으로 판정을 막을 수 있다 (API-설계.md 「2-5. 댓글 (Companion)」). */
  @DisplayName("소프트 삭제·블라인드된 댓글도 본문과 상태를 얻는다.")
  @ParameterizedTest(name = "{0}")
  @EnumSource(
      value = CommentStatus.class,
      names = {"DELETED", "BLINDED"})
  void readInactive(CommentStatus status) {
    long commentId = comment(true, status);

    AdminCommentView view = adminCommentReadService.read(commentId, ADMIN_ID, null);

    assertThat(view.comment().getStatus()).isEqualTo(status);
    assertThat(view.comment().getContent()).isNotBlank();
  }

  @DisplayName("열람 한 번에 감사 로그 한 줄이 남는다.")
  @Test
  void readRecords() {
    long commentId = comment(true, CommentStatus.ACTIVE);

    adminCommentReadService.read(commentId, ADMIN_ID, null);

    AuditLog saved = onlyOne().auditLog();
    assertThat(saved.getActorUserId()).isEqualTo(ADMIN_ID);
    assertThat(saved.getKind()).isEqualTo(AuditKind.SECRET_READ);
    assertThat(saved.getTargetType()).isEqualTo(AuditTargetType.COMMENT);
    assertThat(saved.getTargetId()).isEqualTo(commentId);
  }

  /** 「호출마다」가 걸린 자리다. 조회 결과가 캐시돼도 기록이 빠지면 안 된다. */
  @DisplayName("같은 댓글을 두 번 열람하면 감사 로그가 두 줄 남는다.")
  @Test
  void readTwiceRecordsTwice() {
    long commentId = comment(true, CommentStatus.ACTIVE);

    adminCommentReadService.read(commentId, ADMIN_ID, null);
    adminCommentReadService.read(commentId, ADMIN_ID, null);

    assertThat(auditLogRepository.findSlice(new AuditLogListQuery(null, 20))).hasSize(2);
  }

  @DisplayName("공개 댓글을 열어도 기록이 남는다.")
  @Test
  void readPublicRecords() {
    adminCommentReadService.read(comment(false, CommentStatus.ACTIVE), ADMIN_ID, null);

    assertThat(onlyOne().auditLog().getKind()).isEqualTo(AuditKind.SECRET_READ);
  }

  @DisplayName("신고 번호를 주면 어느 신고를 처리하다 열었는지가 남는다.")
  @Test
  void readCarriesReportId() {
    adminCommentReadService.read(comment(true, CommentStatus.ACTIVE), ADMIN_ID, 5L);

    assertThat(onlyOne().auditLog().getDetail()).isEqualTo("신고 5 처리 중 댓글 본문 열람");
  }

  @DisplayName("신고 번호가 없으면 신고 번호 없는 문구가 남는다.")
  @Test
  void readWithoutReportId() {
    adminCommentReadService.read(comment(true, CommentStatus.ACTIVE), ADMIN_ID, null);

    assertThat(onlyOne().auditLog().getDetail()).isEqualTo("댓글 본문 열람");
  }

  @DisplayName("없는 댓글은 404 다.")
  @Test
  void readMissing() {
    assertThatThrownBy(() -> adminCommentReadService.read(-1L, ADMIN_ID, null))
        .isInstanceOf(BusinessException.class)
        .extracting(thrown -> ((BusinessException) thrown).getErrorCode())
        .isEqualTo(CommentErrorCode.COMMENT_NOT_FOUND);
  }

  /** 순서가 반대이면 존재하지 않는 댓글을 열어봤다는 줄이 남고, 감사 로그는 고칠 수 없다 (I-13). */
  @DisplayName("없는 댓글을 부르면 기록도 남지 않는다.")
  @Test
  void readMissingRecordsNothing() {
    assertThatThrownBy(() -> adminCommentReadService.read(-1L, ADMIN_ID, null))
        .isInstanceOf(BusinessException.class);

    assertThat(auditLogRepository.findSlice(new AuditLogListQuery(null, 20))).isEmpty();
  }

  private long comment(boolean secret, CommentStatus status) {
    return aComment().postId(postId).authorId(AUTHOR_ID).secret(secret).status(status).insert(jdbc);
  }

  private ActedAuditLog onlyOne() {
    List<ActedAuditLog> found = auditLogRepository.findSlice(new AuditLogListQuery(null, 20));

    assertThat(found).hasSize(1);
    return found.get(0);
  }
}
