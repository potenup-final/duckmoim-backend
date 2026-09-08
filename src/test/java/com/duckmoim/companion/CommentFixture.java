package com.duckmoim.companion;

import com.duckmoim.companion.domain.CommentStatus;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 댓글 행을 직접 넣는 테스트 전용 빌더.
 *
 * <p><b>서비스로 만들지 않고 SQL 로 넣는 이유가 둘이다.</b> 첫째로 DELETED · BLINDED 상태의 댓글을 만드는 경로가 아직 없다 — 삭제는 CM-10,
 * 블라인드는 신고 처리 소관이다. 둘째로 대댓글을 부모로 지정하는 검증에 필요한 「이미 대댓글인 댓글」을 도메인 팩터리로는 저장 없이 만들 수 없다.
 *
 * <p>여기서 넣은 행은 JPA 의 영속성 컨텍스트를 지나지 않아 {@code findById} 가 DB 에서 새로 읽는다.
 */
public final class CommentFixture {

  private static final AtomicLong SEQUENCE = new AtomicLong();

  private static final String INSERT =
      """
      INSERT INTO comment (post_id, author_id, parent_id, content, secret, status,
                           created_at, updated_at)
      VALUES (?, ?, ?, ?, ?, ?, COALESCE(?, UTC_TIMESTAMP(6)), UTC_TIMESTAMP(6))
      """;

  private final String content = "픽스처 댓글 " + SEQUENCE.incrementAndGet();
  private long postId;
  private long authorId = 1L;
  private Long parentId;
  private boolean secret;
  private CommentStatus status = CommentStatus.ACTIVE;
  private LocalDateTime createdAt;

  private CommentFixture() {}

  public static CommentFixture aComment() {
    return new CommentFixture();
  }

  public CommentFixture postId(long postId) {
    this.postId = postId;
    return this;
  }

  public CommentFixture authorId(long authorId) {
    this.authorId = authorId;
    return this;
  }

  public CommentFixture parentId(Long parentId) {
    this.parentId = parentId;
    return this;
  }

  public CommentFixture secret(boolean secret) {
    this.secret = secret;
    return this;
  }

  public CommentFixture status(CommentStatus status) {
    this.status = status;
    return this;
  }

  /**
   * 작성 시각을 고정한다. <b>커서 경계 검증에 필요하다</b> — CM-07 이 정렬 키가 같은 데이터를 요구하는데, {@code UTC_TIMESTAMP(6)} 에
   * 맡기면 마이크로초가 갈려 같은 시각이 만들어지지 않는다.
   *
   * <p>주지 않으면 현재 시각이다. 저장은 UTC 다.
   */
  public CommentFixture createdAt(LocalDateTime createdAtUtc) {
    this.createdAt = createdAtUtc;
    return this;
  }

  public long insert(JdbcTemplate jdbc) {
    jdbc.update(INSERT, postId, authorId, parentId, content, secret, status.name(), createdAt);

    return jdbc.queryForObject("SELECT id FROM comment WHERE content = ?", Long.class, content);
  }
}
