package com.duckmoim.catalog.service;

import static com.duckmoim.catalog.EventFixture.anEvent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.duckmoim.catalog.domain.EventKind;
import com.duckmoim.catalog.domain.PlaceKind;
import com.duckmoim.catalog.exception.EventErrorCode;
import com.duckmoim.common.exception.BusinessException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 행사 상세 조회의 검증 기준 (EV-07 · EV-10).
 *
 * <p>실제 MySQL 로 돈다. 검증 기준이 「목록에 없는 행사 ID 로도 상세를 얻는다」 라 <b>목록과 상세가 같은 데이터에서 갈리는지</b>를 봐야 하고, 그것은
 * 저장소를 mock 으로 바꾸면 검증되지 않는다. 테스트 컨벤션이 H2 도 금지했다.
 *
 * <p>시계를 고정한다. 「끝난 행사」 가 오늘 날짜에 매달려 있어 놓아두면 같은 데이터가 어제와 오늘 다르게 나온다.
 *
 * <p>목록 쪽 검증은 {@link EventQueryServiceTest} 가 본다. 여기는 상세만 본다.
 */
@SpringBootTest
@Transactional
class EventDetailServiceTest {

  private static final LocalDate TODAY = LocalDate.of(2026, 9, 4);

  @TestConfiguration
  static class FixedClockConfig {

    @Bean
    @Primary
    Clock fixedClock() {
      return Clock.fixed(
          TODAY.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant(), ZoneId.of("Asia/Seoul"));
    }
  }

  @Autowired private EventQueryService eventQueryService;
  @Autowired private JdbcTemplate jdbc;

  private long seongsu;

  @BeforeEach
  void setUp() {
    jdbc.update("DELETE FROM event_goods");
    jdbc.update("DELETE FROM event");

    seongsu = jdbc.queryForObject("SELECT id FROM region WHERE code = ?", Long.class, "seongsu");
  }

  /**
   * 어제 끝난 행사다. 목록은 이것을 빼고 ({@link EventQueryServiceTest} 「이미 끝난 행사는 목록에 나오지 않는다」) 상세는 준다 — 그 갈림이
   * EV-07 의 검증 기준이다.
   */
  @DisplayName("목록에 없는 끝난 행사도 외부 식별자로 상세를 얻는다.")
  @Test
  void findsEndedEventThatListOmits() {
    anEvent()
        .externalId("pg_8709")
        .regionId(seongsu)
        .startsOn(LocalDate.of(2026, 8, 1))
        .endsOn(LocalDate.of(2026, 9, 3))
        .insert(jdbc);

    assertThat(eventQueryService.findEvent("pg_8709").externalId()).isEqualTo("pg_8709");
  }

  @DisplayName("아직 시작하지 않은 행사도 상세를 얻는다.")
  @Test
  void findsEventThatHasNotStarted() {
    anEvent()
        .externalId("pg_9001")
        .regionId(seongsu)
        .startsOn(LocalDate.of(2026, 12, 1))
        .endsOn(LocalDate.of(2026, 12, 31))
        .insert(jdbc);

    assertThat(eventQueryService.findEvent("pg_9001").externalId()).isEqualTo("pg_9001");
  }

  @DisplayName("없는 외부 식별자로 조회하면 EVENT_NOT_FOUND 다.")
  @Test
  void findEvent_externalIdIsUnknown() {
    assertThatThrownBy(() -> eventQueryService.findEvent("pg_0000"))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(EventErrorCode.EVENT_NOT_FOUND);
  }

  @DisplayName("콘서트 상세에 공연 시작 시각이 실린다.")
  @Test
  void exposesConcertStartTime() {
    anEvent()
        .externalId("mock_con_1")
        .kind(EventKind.CONCERT)
        .placeKind(PlaceKind.CONCERT_HALL)
        .startsAt(LocalTime.of(19, 0))
        .regionId(seongsu)
        .startsOn(LocalDate.of(2026, 9, 19))
        .endsOn(LocalDate.of(2026, 9, 19))
        .insert(jdbc);

    EventSummary concert = eventQueryService.findEvent("mock_con_1");

    assertThat(concert.kind()).isEqualTo(EventKind.CONCERT);
    assertThat(concert.startsAt()).isEqualTo(LocalTime.of(19, 0));
  }

  /** 시작 시각은 콘서트만 갖는다 (EV-10). 생카·팝업은 기간 중 아무 때나 가면 되므로 채울 값이 없다. */
  @DisplayName("콘서트가 아닌 행사의 시작 시각은 비어 있다.")
  @Test
  void leavesStartTimeEmptyForNonConcert() {
    anEvent().externalId("pg_8709").kind(EventKind.POPUP).regionId(seongsu).insert(jdbc);

    assertThat(eventQueryService.findEvent("pg_8709").startsAt()).isNull();
  }

  @DisplayName("상세의 지역이 지역 코드 문자열로 나온다.")
  @Test
  void exposesDistrictCode() {
    anEvent().externalId("pg_8709").regionId(seongsu).insert(jdbc);

    assertThat(eventQueryService.findEvent("pg_8709").district()).isEqualTo("seongsu");
  }

  /** 크롤러가 아직 이 값을 안 내보내지만 계약에는 있다 (화면 계약 1장). 값이 들어온 날 조회에서 터지지 않는지를 여기서 본다. */
  @DisplayName("공연장 장소 종류를 상세로 읽어도 매핑이 깨지지 않는다.")
  @Test
  void readsConcertHallPlaceKind() {
    anEvent()
        .externalId("mock_con_1")
        .kind(EventKind.CONCERT)
        .placeKind(PlaceKind.CONCERT_HALL)
        .regionId(seongsu)
        .insert(jdbc);

    assertThat(eventQueryService.findEvent("mock_con_1").placeKind())
        .isEqualTo(PlaceKind.CONCERT_HALL);
  }
}
