package com.duckmoim.catalog;

import com.duckmoim.catalog.domain.EventKind;
import java.time.LocalDate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 행사 행을 직접 넣는 테스트 전용 빌더.
 *
 * <p>{@code Event} 에 생성 팩터리가 없다. 실제 적재 경로가 크롤러의 SQL upsert(EV-03) 라 그렇고, 여기서 테스트를 위해 도메인에 생성자를 뚫는
 * 것은 프로덕션이 쓰지 않는 문을 만드는 일이다. 그래서 테스트도 SQL 로 넣는다.
 *
 * <p>조회에 쓰이지 않는 값은 기본값으로 숨긴다. 테스트 본문에는 <b>그 테스트가 무엇으로 거르는지</b>만 남는다.
 */
public final class EventFixture {

  private static final String INSERT =
      """
      INSERT INTO event (external_id, source, kind, subject_type, trust, subject, title,
                         starts_on, ends_on, source_url,
                         place_name, place_address, place_lat, place_lng, place_kind, region_id,
                         created_at, updated_at)
      VALUES (?, 'POPGA', ?, 'IDOL', 'PARSED', ?, ?, ?, ?, 'https://example.test/1',
              '테스트 장소', '서울 성동구 1', 37.5, 127.0, 'POPUP_VENUE', ?,
              UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
      """;

  private String externalId;
  private EventKind kind = EventKind.POPUP;
  private String subject = "테스트 대상";
  private String title;
  private LocalDate startsOn = LocalDate.of(2026, 10, 1);
  private LocalDate endsOn = LocalDate.of(2026, 10, 31);
  private long regionId;

  private EventFixture() {}

  public static EventFixture anEvent() {
    return new EventFixture();
  }

  public EventFixture externalId(String externalId) {
    this.externalId = externalId;
    return this;
  }

  public EventFixture kind(EventKind kind) {
    this.kind = kind;
    return this;
  }

  public EventFixture subject(String subject) {
    this.subject = subject;
    return this;
  }

  public EventFixture title(String title) {
    this.title = title;
    return this;
  }

  public EventFixture startsOn(LocalDate startsOn) {
    this.startsOn = startsOn;
    return this;
  }

  public EventFixture endsOn(LocalDate endsOn) {
    this.endsOn = endsOn;
    return this;
  }

  public EventFixture regionId(long regionId) {
    this.regionId = regionId;
    return this;
  }

  /** 넣은 행의 id 를 준다. 커서 경계 검증이 id 순서를 알아야 해서 돌려준다. */
  public long insert(JdbcTemplate jdbc) {
    jdbc.update(INSERT, externalId, kind.name(), subject, title, startsOn, endsOn, regionId);

    return jdbc.queryForObject(
        "SELECT id FROM event WHERE external_id = ?", Long.class, externalId);
  }
}
