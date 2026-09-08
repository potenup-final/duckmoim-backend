package com.duckmoim.companion;

import com.duckmoim.companion.domain.ClosedReason;
import com.duckmoim.companion.domain.PostStatus;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 모집글 행을 직접 넣는 테스트 전용 빌더.
 *
 * <p>작성 팩터리({@code CompanionPost.open})가 있지만 여기서 쓰지 않는다. 마감된 글을 만드는 경로가 아직 없고 (PO-07 · PO-14 소관),
 * 커서 경계 검증에 필요한 <b>정렬 키가 같은 글</b>은 만남시각을 손으로 박아야 만들어진다. {@code EventFixture} 와 같은 이유로 SQL 로 넣는다.
 *
 * <p>조회에 쓰이지 않는 값은 기본값으로 숨긴다. 테스트 본문에는 <b>그 테스트가 무엇으로 거르는지</b>만 남는다.
 */
public final class CompanionPostFixture {

  private static final AtomicLong SEQUENCE = new AtomicLong();

  private static final String INSERT =
      """
      INSERT INTO companion_post (host_id, event_id, event_title, event_image_url,
                                  title, content, meet_at,
                                  meet_place, meet_lat, meet_lng, capacity,
                                  status, closed_reason, created_at, updated_at)
      VALUES (?, ?, ?, ?, ?, ?, ?, '홍대입구역 2번 출구', 37.5, 127.0, ?, ?, ?,
              UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
      """;

  private final String title = "픽스처 모집글 " + SEQUENCE.incrementAndGet();
  private long hostId = 1L;
  private Long eventId;
  private String eventTitle;
  private String eventImageUrl;
  private String content;
  private LocalDateTime meetAt = LocalDateTime.of(2026, 10, 1, 9, 0);
  private Integer capacity;
  private PostStatus status = PostStatus.OPEN;
  private ClosedReason closedReason;

  private CompanionPostFixture() {}

  public static CompanionPostFixture aCompanionPost() {
    return new CompanionPostFixture();
  }

  public CompanionPostFixture hostId(long hostId) {
    this.hostId = hostId;
    return this;
  }

  /**
   * 붙은 행사. <b>행사명과 이미지는 조인이 아니라 스냅샷이라 여기서 함께 박는다</b> (도메인-모델링.md 「3.2 애그리게이트 간 참조 규칙」). 응답의 {@code
   * eventId} 는 외부 식별자라 조회가 {@code event} 표를 조인하므로, 실재하는 행사 id 를 주어야 그 값이 나온다.
   */
  public CompanionPostFixture event(Long eventId, String eventTitle, String eventImageUrl) {
    this.eventId = eventId;
    this.eventTitle = eventTitle;
    this.eventImageUrl = eventImageUrl;
    return this;
  }

  public CompanionPostFixture content(String content) {
    this.content = content;
    return this;
  }

  /**
   * 만남시각을 고정한다. <b>커서 경계 검증에 필요하다</b> — PO-08 의 검증 기준이 「누락·중복 없음」이고 그 앞에 정렬 키가 같은 데이터가 있어야 한다.
   *
   * <p>저장은 UTC 다 (도메인-모델링.md 「4. 엔티티 · 값 객체 · 식별자」).
   */
  public CompanionPostFixture meetAt(LocalDateTime meetAtUtc) {
    this.meetAt = meetAtUtc;
    return this;
  }

  public CompanionPostFixture capacity(Integer capacity) {
    this.capacity = capacity;
    return this;
  }

  public CompanionPostFixture status(PostStatus status) {
    this.status = status;
    return this;
  }

  /** 배지 문구가 이 값으로 갈린다 — MANUAL 이면 「모집 완료」, MEET_TIME_PASSED 면 「종료」 다 (화면 계약). */
  public CompanionPostFixture closedReason(ClosedReason closedReason) {
    this.closedReason = closedReason;
    return this;
  }

  /** 넣은 행의 id 를 준다. */
  public long insert(JdbcTemplate jdbc) {
    jdbc.update(
        INSERT,
        hostId,
        eventId,
        eventTitle,
        eventImageUrl,
        title,
        content,
        meetAt,
        capacity,
        status.name(),
        closedReason == null ? null : closedReason.name());

    return jdbc.queryForObject("SELECT id FROM companion_post WHERE title = ?", Long.class, title);
  }
}
