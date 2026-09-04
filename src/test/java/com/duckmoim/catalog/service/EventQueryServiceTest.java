package com.duckmoim.catalog.service;

import static com.duckmoim.catalog.EventFixture.anEvent;
import static org.assertj.core.api.Assertions.assertThat;

import com.duckmoim.catalog.domain.EventCursor;
import com.duckmoim.catalog.domain.EventKind;
import com.duckmoim.catalog.domain.EventQuery;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 행사 목록 조회의 검증 기준 (EV-05 · EV-06).
 *
 * <p>실제 MySQL 로 돈다. 커서 경계는 정렬과 비교 연산이 개입해서 mock 으로는 검증되지 않고, 테스트 컨벤션이 H2 도 금지했다.
 *
 * <p>매 테스트가 시드(V3, 205건)를 지우고 자기 데이터만 넣는다. {@code @Transactional} 롤백이 시드를 되돌린다.
 */
@SpringBootTest
@Transactional
class EventQueryServiceTest {

  private static final LocalDate OCT_1 = LocalDate.of(2026, 10, 1);
  private static final LocalDate OCT_31 = LocalDate.of(2026, 10, 31);

  @Autowired private EventQueryService eventQueryService;
  @Autowired private JdbcTemplate jdbc;

  private long seongsu;
  private long gangnam;

  @BeforeEach
  void setUp() {
    jdbc.update("DELETE FROM event_goods");
    jdbc.update("DELETE FROM event");

    seongsu = regionId("seongsu");
    gangnam = regionId("gangnam");
  }

  private long regionId(String code) {
    return jdbc.queryForObject("SELECT id FROM region WHERE code = ?", Long.class, code);
  }

  private static EventQuery query(EventKind kind, Long regionId, String keyword) {
    return new EventQuery(kind, regionId, null, null, keyword, null, 20);
  }

  private List<String> externalIdsOf(EventSlice slice) {
    return slice.events().stream().map(EventSummary::externalId).toList();
  }

  /**
   * 검증 기준이 "필터 조합별 결과 정확" 이라 조합을 세운다.
   *
   * <p>넷을 각각 켜고 끄면 16가지인데, 의미가 겹치는 조합을 빼고 <b>필터 하나씩 · 둘 · 넷 전부</b>를 남겼다.
   */
  private void givenFourEvents() {
    // 팝업 · 성수 · 10/01~10/10 · 아이브
    anEvent()
        .externalId("e1")
        .kind(EventKind.POPUP)
        .regionId(seongsu)
        .startsOn(OCT_1)
        .endsOn(LocalDate.of(2026, 10, 10))
        .subject("아이브")
        .title("IVE 팝업")
        .insert(jdbc);

    // 팝업 · 강남 · 10/01~10/05 · 아이브
    anEvent()
        .externalId("e2")
        .kind(EventKind.POPUP)
        .regionId(gangnam)
        .startsOn(OCT_1)
        .endsOn(LocalDate.of(2026, 10, 5))
        .subject("아이브")
        .title("아이브 카페")
        .insert(jdbc);

    // 생카 · 성수 · 10/03 하루 · 르세라핌
    anEvent()
        .externalId("e3")
        .kind(EventKind.BIRTHDAY_CAFE)
        .regionId(seongsu)
        .startsOn(LocalDate.of(2026, 10, 3))
        .endsOn(LocalDate.of(2026, 10, 3))
        .subject("르세라핌")
        .insert(jdbc);

    // 콘서트 · 성수 · 11/01 하루 · 아이브
    anEvent()
        .externalId("e4")
        .kind(EventKind.CONCERT)
        .regionId(seongsu)
        .startsOn(LocalDate.of(2026, 11, 1))
        .endsOn(LocalDate.of(2026, 11, 1))
        .subject("아이브")
        .title("IVE 콘서트")
        .insert(jdbc);
  }

  @DisplayName("필터 없이 조회하면 시작일 빠른 순으로 나온다.")
  @Test
  void findEvents() {
    // given
    givenFourEvents();

    // when
    EventSlice slice = eventQueryService.findEvents(query(null, null, null));

    // then — e1 과 e2 는 시작일이 같아 id 순으로 갈린다
    assertThat(externalIdsOf(slice)).containsExactly("e1", "e2", "e3", "e4");
  }

  @DisplayName("종류로 거르면 그 종류의 행사만 나온다.")
  @Test
  void findEvents_byKind() {
    // given
    givenFourEvents();

    // when
    EventSlice slice = eventQueryService.findEvents(query(EventKind.POPUP, null, null));

    // then
    assertThat(externalIdsOf(slice)).containsExactly("e1", "e2");
  }

  @DisplayName("지역으로 거르면 그 지역의 행사만 나온다.")
  @Test
  void findEvents_byRegion() {
    // given
    givenFourEvents();

    // when
    EventSlice slice = eventQueryService.findEvents(query(null, seongsu, null));

    // then
    assertThat(externalIdsOf(slice)).containsExactly("e1", "e3", "e4");
  }

  @DisplayName("행사일 범위로 거르면 그 기간에 열려 있는 행사만 나온다.")
  @Test
  void findEvents_byPeriod() {
    // given
    givenFourEvents();

    // when — 10/06 에는 e1 만 아직 열려 있다. e2 는 10/05 에 끝났고 e3 는 10/03 하루였다
    EventSlice slice =
        eventQueryService.findEvents(
            new EventQuery(null, null, LocalDate.of(2026, 10, 6), OCT_31, null, null, 20));

    // then
    assertThat(externalIdsOf(slice)).containsExactly("e1");
  }

  @DisplayName("행사 시작 전에 걸친 범위로 걸러도 기간이 겹치면 나온다.")
  @Test
  void findEvents_byPeriodOverlapping() {
    // given
    givenFourEvents();

    // when — 9월 말에서 10/02 까지. 셋 다 이 시점에 이미 시작했거나 진행 중이다
    EventSlice slice =
        eventQueryService.findEvents(
            new EventQuery(
                null, null, LocalDate.of(2026, 9, 25), LocalDate.of(2026, 10, 2), null, null, 20));

    // then — e3 는 10/03 시작이라 아직 안 열렸다
    assertThat(externalIdsOf(slice)).containsExactly("e1", "e2");
  }

  @DisplayName("키워드로 거르면 대상명이나 원제에 그 말이 든 행사만 나온다.")
  @Test
  void findEvents_byKeyword() {
    // given
    givenFourEvents();

    // when
    EventSlice slice = eventQueryService.findEvents(query(null, null, "아이브"));

    // then — e3 만 르세라핌이라 빠진다
    assertThat(externalIdsOf(slice)).containsExactly("e1", "e2", "e4");
  }

  @DisplayName("키워드가 원제에만 있어도 걸린다.")
  @Test
  void findEvents_byKeywordInTitle() {
    // given
    givenFourEvents();

    // when — subject 는 전부 한글이고 "IVE" 는 title 에만 있다
    EventSlice slice = eventQueryService.findEvents(query(null, null, "IVE"));

    // then
    assertThat(externalIdsOf(slice)).containsExactly("e1", "e4");
  }

  static Stream<Arguments> filterCombinations() {
    return Stream.of(
        Arguments.of("종류만", EventKind.POPUP, null, null, List.of("e1", "e2")),
        Arguments.of("종류와 지역", EventKind.POPUP, "seongsu", null, List.of("e1")),
        Arguments.of("종류와 키워드", EventKind.CONCERT, null, "아이브", List.of("e4")),
        Arguments.of("지역과 키워드", null, "seongsu", "아이브", List.of("e1", "e4")),
        Arguments.of("셋 다", EventKind.POPUP, "seongsu", "아이브", List.of("e1")),
        Arguments.of("맞는 게 없는 조합", EventKind.BIRTHDAY_CAFE, "gangnam", null, List.of()));
  }

  @DisplayName("필터를 조합해도 결과가 정확하다.")
  @ParameterizedTest(name = "{0}")
  @MethodSource("filterCombinations")
  void findEvents_byCombination(
      String description,
      EventKind kind,
      String regionCode,
      String keyword,
      List<String> expected) {
    // given
    givenFourEvents();
    Long regionId = regionCode == null ? null : regionId(regionCode);

    // when
    EventSlice slice = eventQueryService.findEvents(query(kind, regionId, keyword));

    // then
    assertThat(externalIdsOf(slice)).isEqualTo(expected);
  }

  @DisplayName("조건에 맞는 행사가 없으면 빈 목록과 hasNext=false 를 준다.")
  @Test
  void findEvents_hasNoMatch() {
    // given
    givenFourEvents();

    // when
    EventSlice slice = eventQueryService.findEvents(query(null, null, "없는대상명"));

    // then
    assertThat(slice.events()).isEmpty();
    assertThat(slice.hasNext()).isFalse();
    assertThat(slice.nextCursor()).isNull();
  }

  /**
   * EV-06 의 검증 기준을 위해 <b>시작일이 전부 같은</b> 행사를 만든다.
   *
   * <p>이래야 페이지 경계가 시작일 안에서 갈린다. 시작일이 다 다르면 커서에 id 가 없어도 우연히 통과한다.
   */
  private void givenFiveEventsOnSameDay() {
    LocalDate sameDay = LocalDate.of(2026, 12, 1);

    for (int i = 1; i <= 5; i++) {
      anEvent()
          .externalId("same-" + i)
          .regionId(seongsu)
          .startsOn(sameDay)
          .endsOn(sameDay)
          .insert(jdbc);
    }
  }

  private List<String> readAllByCursor(int size) {
    List<String> read = new ArrayList<>();
    EventCursor cursor = null;

    while (true) {
      EventSlice slice =
          eventQueryService.findEvents(new EventQuery(null, null, null, null, null, cursor, size));
      read.addAll(externalIdsOf(slice));

      if (!slice.hasNext()) {
        return read;
      }
      cursor = slice.nextCursor();
    }
  }

  @DisplayName("시작일이 같은 행사가 페이지 경계에 걸려도 누락되지 않는다.")
  @Test
  void findEvents_byCursorMissesNothing() {
    // given
    givenFiveEventsOnSameDay();

    // when
    List<String> read = readAllByCursor(2);

    // then
    assertThat(read).containsExactly("same-1", "same-2", "same-3", "same-4", "same-5");
  }

  @DisplayName("시작일이 같은 행사가 페이지 경계에 걸려도 중복되지 않는다.")
  @Test
  void findEvents_byCursorDuplicatesNothing() {
    // given
    givenFiveEventsOnSameDay();

    // when
    List<String> read = readAllByCursor(2);

    // then
    assertThat(read).doesNotHaveDuplicates();
  }

  @DisplayName("페이지 크기가 1이어도 전체를 정확히 한 번씩 읽는다.")
  @Test
  void findEvents_byCursorOfSizeOne() {
    // given — 경계가 매번 시작일 안에서 갈리는 가장 빡빡한 경우다
    givenFiveEventsOnSameDay();

    // when
    List<String> read = readAllByCursor(1);

    // then
    assertThat(read).containsExactly("same-1", "same-2", "same-3", "same-4", "same-5");
  }

  @DisplayName("마지막 페이지에서 nextCursor 는 null 이고 hasNext 는 false 다.")
  @Test
  void findEvents_atLastPage() {
    // given
    givenFiveEventsOnSameDay();

    // when — 다섯 건을 한 번에 담을 수 있는 크기다
    EventSlice slice = eventQueryService.findEvents(query(null, null, null));

    // then
    assertThat(slice.events()).hasSize(5);
    assertThat(slice.hasNext()).isFalse();
    assertThat(slice.nextCursor()).isNull();
  }

  @DisplayName("페이지가 딱 떨어져도 다음 페이지가 있다고 하지 않는다.")
  @Test
  void findEvents_atExactPageBoundary() {
    // given — 5건을 5씩 읽으면 여분이 없다. size + 1 판정이 여기서 틀리기 쉽다
    givenFiveEventsOnSameDay();

    // when
    EventSlice slice =
        eventQueryService.findEvents(new EventQuery(null, null, null, null, null, null, 5));

    // then
    assertThat(slice.hasNext()).isFalse();
    assertThat(slice.nextCursor()).isNull();
  }

  @DisplayName("필터를 건 채로 커서를 넘겨도 누락·중복이 없다.")
  @Test
  void findEvents_byCursorWithFilter() {
    // given — 같은 날 팝업 3건과 생카 2건. 필터가 커서 조건과 함께 걸려야 한다
    LocalDate sameDay = LocalDate.of(2026, 12, 1);
    for (int i = 1; i <= 3; i++) {
      anEvent()
          .externalId("popup-" + i)
          .kind(EventKind.POPUP)
          .regionId(seongsu)
          .startsOn(sameDay)
          .endsOn(sameDay)
          .insert(jdbc);
    }
    for (int i = 1; i <= 2; i++) {
      anEvent()
          .externalId("cafe-" + i)
          .kind(EventKind.BIRTHDAY_CAFE)
          .regionId(seongsu)
          .startsOn(sameDay)
          .endsOn(sameDay)
          .insert(jdbc);
    }

    // when
    List<String> read = new ArrayList<>();
    EventCursor cursor = null;
    boolean hasNext = true;
    while (hasNext) {
      EventSlice slice =
          eventQueryService.findEvents(
              new EventQuery(EventKind.POPUP, null, null, null, null, cursor, 2));
      read.addAll(externalIdsOf(slice));
      hasNext = slice.hasNext();
      cursor = slice.nextCursor();
    }

    // then
    assertThat(read).containsExactly("popup-1", "popup-2", "popup-3");
  }

  @DisplayName("size 상한을 넘겨 요청해도 상한만큼만 준다.")
  @Test
  void findEvents_exceedsMaxSize() {
    // given
    givenFourEvents();

    // when
    EventSlice slice =
        eventQueryService.findEvents(new EventQuery(null, null, null, null, null, null, 9999));

    // then — 넣은 게 4건뿐이라 건수로는 못 보고, 상한이 걸렸는지를 조건으로 본다
    assertThat(slice.events()).hasSize(4);
    assertThat(slice.hasNext()).isFalse();
  }
}
