package com.duckmoim.companion.service;

import static com.duckmoim.companion.CommentFixture.aComment;
import static com.duckmoim.companion.CompanionPostFixture.aCompanionPost;
import static org.assertj.core.api.Assertions.assertThat;

import com.duckmoim.companion.infra.CommentRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 댓글 작성이 아웃박스에 무엇을 적는지 (NT-01 · NT-06).
 *
 * <p>실제 MySQL 로 돈다. 수신자가 모집글과 부모 댓글에서 나오므로 그 둘이 저장소에 있어야 판정된다.
 *
 * <p><b>읽기 전에 {@code flush} 한다.</b> 아웃박스 INSERT 는 같은 영속성 컨텍스트에 쌓여 있어 {@code JdbcTemplate} 으로는 보이지
 * 않는다 — {@code CommentRepository.flush()} 가 컨텍스트 전체를 내보낸다.
 *
 * <p>롤백 여부는 여기서 보지 않는다. 그것은 {@code CommentWriteOutboxTransactionTest} 가 트랜잭션을 직접 열어서 본다.
 */
@SpringBootTest
@Transactional
class CommentNotificationOutboxTest {

  /** {@code CompanionPostFixture} · {@code CommentFixture} 의 기본값이라 셋을 서로 다르게 쓴다. */
  private static final long HOST_ID = 1L;

  private static final long PARENT_AUTHOR_ID = 7L;
  private static final long WRITER_ID = 8L;

  @Autowired private CommentCommandService commentCommandService;
  @Autowired private CommentRepository commentRepository;
  @Autowired private JdbcTemplate jdbc;

  private long openPostId;

  @BeforeEach
  void setUp() {
    openPostId = aCompanionPost().hostId(HOST_ID).insert(jdbc);
  }

  @DisplayName("내 모집글에 댓글이 달리면 방장에게 보낼 행이 쌓인다.")
  @Test
  void write_notifiesHost() {
    // when
    WrittenComment written = commentCommandService.write(rootCommandBy(WRITER_ID));

    // then
    assertThat(outboxRows())
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row).containsEntry("recipient_id", HOST_ID);
              assertThat(row).containsEntry("kind", "POST_COMMENTED");
              assertThat(row).containsEntry("post_id", openPostId);
              assertThat(row).containsEntry("comment_id", written.id());
              assertThat(row).containsEntry("status", "PENDING");
            });
  }

  /**
   * 「내 댓글에 답글이 달렸다」 하나만 쌓인다.
   *
   * <p><b>방장 몫이 함께 쌓이지 않는 것도 여기서 본다.</b> 행이 하나라는 것이 그 증거다 — NT-06 이 두 종류를 갈랐고, 답글 한 건으로 둘을 만들면 알림함이
   * 같은 사실로 두 번 채워진다.
   */
  @DisplayName("내 댓글에 답글이 달리면 부모 댓글 작성자에게만 보낼 행이 쌓인다.")
  @Test
  void writeReply_notifiesParentAuthorOnly() {
    // given
    long parentId = aComment().postId(openPostId).authorId(PARENT_AUTHOR_ID).insert(jdbc);

    // when
    commentCommandService.write(replyCommandBy(WRITER_ID, parentId));

    // then
    assertThat(outboxRows())
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row).containsEntry("recipient_id", PARENT_AUTHOR_ID);
              assertThat(row).containsEntry("kind", "COMMENT_REPLIED");
            });
  }

  @DisplayName("자기 모집글에 자기가 댓글을 달면 행을 만들지 않는다.")
  @Test
  void write_authorIsHost() {
    // when
    commentCommandService.write(rootCommandBy(HOST_ID));

    // then
    assertThat(outboxRows()).isEmpty();
  }

  @DisplayName("자기 댓글에 자기가 답글을 달면 행을 만들지 않는다.")
  @Test
  void writeReply_authorIsParentAuthor() {
    // given
    long parentId = aComment().postId(openPostId).authorId(WRITER_ID).insert(jdbc);

    // when
    commentCommandService.write(replyCommandBy(WRITER_ID, parentId));

    // then
    assertThat(outboxRows()).isEmpty();
  }

  private CommentWriteCommand rootCommandBy(long authorId) {
    return new CommentWriteCommand(openPostId, authorId, null, "저 갈게요!", false);
  }

  private CommentWriteCommand replyCommandBy(long authorId, long parentId) {
    return new CommentWriteCommand(openPostId, authorId, parentId, "저도요", false);
  }

  /**
   * 이 테스트가 만든 모집글의 행만 센다.
   *
   * <p><b>표 전체를 보면 안 된다.</b> 트랜잭션을 쓰지 않는 테스트(동시 작성 검증 같은 것)가 커밋해 둔 행이 남아 있어서, 처음 짰을 때 102 건이 함께
   * 잡혔다. 모집글 번호는 매 테스트가 새로 만들어 겹치지 않는다.
   */
  private List<Map<String, Object>> outboxRows() {
    commentRepository.flush();

    return jdbc.queryForList(
        "SELECT recipient_id, kind, post_id, comment_id, status"
            + " FROM notification_outbox WHERE post_id = ?",
        openPostId);
  }
}
