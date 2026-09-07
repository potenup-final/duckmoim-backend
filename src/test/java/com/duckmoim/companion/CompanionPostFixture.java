package com.duckmoim.companion;

import com.duckmoim.companion.domain.PostStatus;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 모집글 행을 직접 넣는 테스트 전용 빌더.
 *
 * <p>{@code CompanionPost} 에 생성 팩터리가 없다 — Comment 를 먼저 개발하려고 세운 최소 형태라 읽기만 된다. 생성과 마감은 Companion
 * 담당이 채운다. 테스트를 위해 도메인에 생성자를 뚫는 것은 프로덕션이 쓰지 않는 문을 만드는 일이라, EventFixture 와 같이 SQL 로 넣는다.
 *
 * <p>댓글이 이 표에서 읽는 것은 상태 하나뿐이다. 나머지 컬럼은 NOT NULL 을 채우기 위한 값이고 테스트 본문에 나오지 않는다.
 */
public final class CompanionPostFixture {

  private static final AtomicLong SEQUENCE = new AtomicLong();

  private static final String INSERT =
      """
      INSERT INTO companion_post (host_id, title, meet_at, meet_place, meet_lat, meet_lng,
                                  status, created_at, updated_at)
      VALUES (1, ?, ?, '홍대입구역 2번 출구', 37.5, 127.0, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
      """;

  private final String title = "픽스처 모집글 " + SEQUENCE.incrementAndGet();
  private PostStatus status = PostStatus.OPEN;

  private CompanionPostFixture() {}

  public static CompanionPostFixture aCompanionPost() {
    return new CompanionPostFixture();
  }

  public CompanionPostFixture status(PostStatus status) {
    this.status = status;
    return this;
  }

  /** 넣은 행의 id 를 준다. 댓글이 postId 로만 참조하므로 테스트에 필요한 것은 이 값뿐이다. */
  public long insert(JdbcTemplate jdbc) {
    jdbc.update(INSERT, title, LocalDateTime.of(2026, 10, 1, 9, 0), status.name());

    return jdbc.queryForObject("SELECT id FROM companion_post WHERE title = ?", Long.class, title);
  }
}
