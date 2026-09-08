package com.duckmoim.companion.service;

import static com.duckmoim.companion.CommentFixture.aComment;
import static com.duckmoim.companion.CompanionPostFixture.aCompanionPost;
import static org.assertj.core.api.Assertions.assertThat;

import com.duckmoim.admin.domain.AuditLogListQuery;
import com.duckmoim.admin.infra.AuditLogRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 감사 로그가 블라인드와 <b>같은 트랜잭션</b>에 남는지 (AD-07 · I-13).
 *
 * <p><b>클래스에 {@code @Transactional} 을 붙이지 않는다.</b> 붙이면 테스트가 통째로 롤백되어 「부르는 쪽이 롤백하면 기록도 사라진다」를 볼 수 없다
 * — 무엇이 롤백을 일으켰는지 구분되지 않는다. 여기서는 트랜잭션을 직접 열고 직접 되돌린다.
 *
 * <p><b>이 검사가 지키는 것.</b> 기록기가 {@code REQUIRES_NEW} 로 떨어지면 전이가 실패해도 기록만 남아 <b>일어나지 않은 조치</b>가 장부에
 * 오르고, 감사 로그는 고칠 수 없어 그 줄이 영영 남는다.
 *
 * <p>롤백되므로 남기는 행이 없다. 실패할 때만 흔적이 남고, 그때는 이 검사가 빨간불이다.
 */
@SpringBootTest
class AdminCommentBlindTransactionTest {

  private static final long ADMIN_ID = 6L;
  private static final long AUTHOR_ID = 2L;

  @Autowired private AdminCommentCommandService adminCommentCommandService;
  @Autowired private AuditLogRepository auditLogRepository;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private JdbcTemplate jdbc;

  @DisplayName("부르는 쪽이 롤백하면 감사 로그도 남지 않는다.")
  @Test
  void recordJoinsCallerTransaction() {
    long before = countAuditLogs();

    transactionTemplate.executeWithoutResult(
        status -> {
          long postId = aCompanionPost().insert(jdbc);
          long commentId = aComment().postId(postId).authorId(AUTHOR_ID).insert(jdbc);

          adminCommentCommandService.blind(commentId, ADMIN_ID);

          status.setRollbackOnly();
        });

    assertThat(countAuditLogs()).isEqualTo(before);
  }

  private long countAuditLogs() {
    return auditLogRepository.findSlice(new AuditLogListQuery(null, 1000)).size();
  }
}
