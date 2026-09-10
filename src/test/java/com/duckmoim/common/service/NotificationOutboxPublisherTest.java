package com.duckmoim.common.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 발행기의 계약 (NT-01).
 *
 * <p>실제 MySQL 로 돈다. 어느 컬럼에 무엇이 들어갔는지를 보려면 표를 읽어야 하고, 저장소는 조회를 열어 두지 않았다 (NT-02 소관).
 *
 * <p><b>클래스에 {@code @Transactional} 을 붙이지 않는다.</b> 발행기가 {@code MANDATORY} 라 트랜잭션 유무 자체가 검사 대상이고,
 * 붙이면 「트랜잭션 없이 부르면 거절한다」를 볼 수 없다.
 */
@SpringBootTest
class NotificationOutboxPublisherTest {

  private static final long HOST_ID = 1L;
  private static final long AUTHOR_ID = 2L;
  private static final long POST_ID = 10L;
  private static final long COMMENT_ID = 100L;

  @Autowired private NotificationOutboxPublisher publisher;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void setUp() {
    jdbc.update("DELETE FROM notification_outbox");
  }

  @DisplayName("아웃박스 행은 보낼 것을 담아 아직 보내지 않은 상태로 쌓인다.")
  @Test
  void postCommented() {
    // when
    transactionTemplate.executeWithoutResult(
        status -> publisher.postCommented(HOST_ID, AUTHOR_ID, POST_ID, COMMENT_ID));

    // then — 워커가 Companion 에 물어볼 수 없어 필요한 값이 행에 다 있어야 한다
    assertThat(rows())
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row).containsEntry("recipient_id", HOST_ID);
              assertThat(row).containsEntry("kind", "POST_COMMENTED");
              assertThat(row).containsEntry("post_id", POST_ID);
              assertThat(row).containsEntry("comment_id", COMMENT_ID);
              assertThat(row).containsEntry("status", "PENDING");
            });
  }

  @DisplayName("답글 알림은 부모 댓글 작성자를 수신자로 쌓인다.")
  @Test
  void commentReplied() {
    // when
    transactionTemplate.executeWithoutResult(
        status -> publisher.commentReplied(AUTHOR_ID, HOST_ID, POST_ID, COMMENT_ID));

    // then
    assertThat(rows())
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row).containsEntry("kind", "COMMENT_REPLIED");
              assertThat(row).containsEntry("recipient_id", AUTHOR_ID);
            });
  }

  @DisplayName("수신자가 행동한 사람과 같으면 아웃박스 행을 만들지 않는다.")
  @Test
  void publish_recipientIsActor() {
    // when — 자기 모집글에 자기가 댓글을 단 경우다
    transactionTemplate.executeWithoutResult(
        status -> publisher.postCommented(AUTHOR_ID, AUTHOR_ID, POST_ID, COMMENT_ID));

    // then
    assertThat(rows()).isEmpty();
  }

  @DisplayName("트랜잭션 없이 발행하면 거절한다.")
  @Test
  void publish_withoutTransaction() {
    // when & then — 행만 따로 커밋되면 「댓글이 있으면 알림도 있다」가 조용히 깨진다
    assertThatThrownBy(() -> publisher.postCommented(HOST_ID, AUTHOR_ID, POST_ID, COMMENT_ID))
        .isInstanceOf(IllegalTransactionStateException.class);

    assertThat(rows()).isEmpty();
  }

  private List<Map<String, Object>> rows() {
    return jdbc.queryForList(
        "SELECT recipient_id, kind, post_id, comment_id, status FROM notification_outbox");
  }
}
