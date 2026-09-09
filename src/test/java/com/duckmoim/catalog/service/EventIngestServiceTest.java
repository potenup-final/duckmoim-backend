package com.duckmoim.catalog.service;

import static com.duckmoim.catalog.EventFixture.anEvent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.duckmoim.catalog.domain.EventCrawl;
import com.duckmoim.catalog.domain.EventKind;
import com.duckmoim.catalog.domain.EventQuery;
import com.duckmoim.catalog.domain.EventSource;
import com.duckmoim.catalog.domain.PlaceKind;
import com.duckmoim.catalog.domain.SubjectType;
import com.duckmoim.catalog.domain.Trust;
import com.duckmoim.catalog.exception.EventErrorCode;
import com.duckmoim.common.exception.BusinessException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 행사 벌크 적재의 검증 기준 (EV-03 · EV-04).
 *
 * <p>실제 MySQL 로 돈다. 검증 기준이 「재실행해도 중복 생성 없음」이고 그것을 지키는 것이 {@code uk_event_external_id} 라, 저장소를 mock
 * 으로 바꾸면 정작 봐야 할 것이 사라진다. 테스트 컨벤션이 H2 도 금지했다.
 *
 * <p>목록에서 유령이 걷히는지는 {@link EventQueryService} 를 함께 불러 본다 — 적재가 값을 남기는 것과 목록이 그 값으로 거르는 것이 갈라져 있으면
 * 어느 한쪽만 초록불이 난다.
 */
@SpringBootTest
@Transactional
@DisplayName("행사 벌크 적재")
class EventIngestServiceTest {

  @Autowired private EventIngestService eventIngestService;
  @Autowired private EventQueryService eventQueryService;
  @Autowired private JdbcTemplate jdbcTemplate;

  private long regionId;

  @BeforeEach
  void setUp() {
    jdbcTemplate.update("DELETE FROM event");
    regionId = jdbcTemplate.queryForObject("SELECT id FROM region LIMIT 1", Long.class);
  }

  @Test
  @DisplayName("처음 받은 행사는 새로 만든다.")
  void ingest_createsUnseenEvents() {
    EventIngestResult result = eventIngestService.ingest(commandOf("kopis_1", "kopis_2"));

    assertThat(result.created()).isEqualTo(2);
    assertThat(result.updated()).isZero();
    assertThat(result.total()).isEqualTo(2);
  }

  /**
   * EV-03 의 검증 기준 — 「재실행해도 중복 생성 없음」.
   *
   * <p>같은 배치를 두 번 넣는다. 두 번째가 {@code created} 0 · {@code updated} 2 이고 {@code total} 이 그대로여야 한다.
   * <b>{@code total} 은 크롤러가 멱등성을 확인하는 값이기도 하다</b> — 러너는 DB 를 세지 못한다 (D-8).
   */
  @Test
  @DisplayName("같은 배치를 두 번 받아도 행이 늘지 않는다.")
  void ingest_isIdempotent() {
    eventIngestService.ingest(commandOf("kopis_1", "kopis_2"));

    EventIngestResult again = eventIngestService.ingest(commandOf("kopis_1", "kopis_2"));

    assertThat(again.created()).isZero();
    assertThat(again.updated()).isEqualTo(2);
    assertThat(again.total()).isEqualTo(2);
  }

  @Test
  @DisplayName("이미 있는 행사는 수집한 값으로 갱신한다.")
  void ingest_updatesExistingEvent() {
    anEvent().externalId("pg_1").subject("옛 대상").regionId(regionId).insert(jdbcTemplate);

    eventIngestService.ingest(commandOf("pg_1"));

    String subject =
        jdbcTemplate.queryForObject(
            "SELECT subject FROM event WHERE external_id = 'pg_1'", String.class);
    assertThat(subject).isEqualTo("수집한 대상");
  }

  /**
   * 원본에서 사라진 행사가 목록에서 걷힌다 (D-7).
   *
   * <p>기간이 남았는데 오래 안 잡힌 행을 만들어 둔다. 적재는 그 행을 요청에 싣지 않으므로 갱신하지 않고, 목록은 그 행을 빼야 한다.
   *
   * <p><b>기간이 지난 행사로는 검증할 수 없다.</b> 그쪽은 {@code endsOn} 필터가 이미 거르고 있어서, 빠진 것이 숨김 때문인지 만료 때문인지 갈리지
   * 않는다.
   */
  @Test
  @DisplayName("오래 안 잡힌 행사는 목록에서 빠진다.")
  void ingest_hidesStaleEvent() {
    anEvent()
        .externalId("om_gone")
        .endsOn(LocalDate.now().plusMonths(1))
        .lastCrawledAt(Instant.now().minus(Duration.ofDays(7)))
        .regionId(regionId)
        .insert(jdbcTemplate);

    EventIngestResult result = eventIngestService.ingest(commandOf("kopis_1"));

    assertThat(result.hidden()).isEqualTo(1);
    assertThat(externalIdsInListing()).containsExactly("kopis_1");
  }

  @Test
  @DisplayName("방금 잡힌 행사는 목록에 남는다.")
  void ingest_keepsFreshEvent() {
    eventIngestService.ingest(commandOf("kopis_1"));

    EventIngestResult again = eventIngestService.ingest(commandOf("kopis_1"));

    assertThat(again.hidden()).isZero();
    assertThat(externalIdsInListing()).containsExactly("kopis_1");
  }

  /**
   * 없는 지역 코드는 400 이다.
   *
   * <p>기본 구역으로 떨어뜨리지 않는다 — 지도와 필터가 이 값으로 도는데 조용히 담으면 화면에서는 정상으로 보이면서 핀이 엉뚱한 곳에 선다.
   */
  @Test
  @DisplayName("등록되지 않은 지역 코드를 보내면 적재를 거부한다.")
  void ingest_regionCodeIsUnknown() {
    EventIngestCommand command =
        new EventIngestCommand(List.of(new EventIngestCommand.Item(crawlOf("kopis_1"), "busan")));

    assertThatThrownBy(() -> eventIngestService.ingest(command))
        .isInstanceOf(BusinessException.class)
        .extracting(exception -> ((BusinessException) exception).getErrorCode())
        .isEqualTo(EventErrorCode.EVENT_REGION_UNKNOWN);
  }

  private List<String> externalIdsInListing() {
    return eventQueryService
        .findEvents(new EventQuery(null, null, null, null, null, null, 20))
        .events()
        .stream()
        .map(EventSummary::externalId)
        .toList();
  }

  private EventIngestCommand commandOf(String... externalIds) {
    String districtCode =
        jdbcTemplate.queryForObject("SELECT code FROM region WHERE id = ?", String.class, regionId);

    return new EventIngestCommand(
        java.util.Arrays.stream(externalIds)
            .map(externalId -> new EventIngestCommand.Item(crawlOf(externalId), districtCode))
            .toList());
  }

  private EventCrawl crawlOf(String externalId) {
    return new EventCrawl(
        externalId,
        EventSource.KOPIS,
        EventKind.CONCERT,
        SubjectType.IDOL,
        Trust.PARSED,
        "수집한 대상",
        "수집한 원제",
        LocalDate.now(),
        LocalDate.now().plusMonths(1),
        null,
        null,
        null,
        null,
        "https://kopis.or.kr/1",
        null,
        null,
        null,
        "KSPO DOME",
        "서울 송파구 올림픽로 424",
        new BigDecimal("37.5209000"),
        new BigDecimal("127.0736000"),
        PlaceKind.CONCERT_HALL);
  }
}
