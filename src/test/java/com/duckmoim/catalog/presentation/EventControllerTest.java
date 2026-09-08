package com.duckmoim.catalog.presentation;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.duckmoim.auth.presentation.ImportSecurity;
import com.duckmoim.catalog.domain.EventCursor;
import com.duckmoim.catalog.domain.EventKind;
import com.duckmoim.catalog.domain.PlaceKind;
import com.duckmoim.catalog.domain.SubjectType;
import com.duckmoim.catalog.domain.Trust;
import com.duckmoim.catalog.exception.EventErrorCode;
import com.duckmoim.catalog.service.EventQueryService;
import com.duckmoim.catalog.service.EventSlice;
import com.duckmoim.catalog.service.EventSummary;
import com.duckmoim.common.exception.BusinessException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP 계약만 본다 (테스트 컨벤션 · 테스트 계층).
 *
 * <p>필터 조합과 커서 경계는 통합 테스트가 본다. 여기서 다시 검증하면 같은 규칙을 두 곳에서 관리하게 된다.
 */
@WebMvcTest(EventController.class)
@ImportSecurity
class EventControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private EventQueryService eventQueryService;

  private static EventSummary summary() {
    return new EventSummary(
        1L,
        "pg_8709",
        EventKind.POPUP,
        SubjectType.IDOL,
        Trust.PARSED,
        "BIGBANG",
        "빅뱅 20주년 미디어 전시",
        LocalDate.of(2026, 8, 24),
        LocalDate.of(2026, 9, 27),
        "매일 10:00 ~ 22:00",
        LocalTime.of(19, 30),
        "MD 구매 특전 포토카드 1장 랜덤 증정",
        "성인 인증 필요",
        "https://cdn.example.test/1.webp",
        "https://www.instagram.com/p/Db8AYzJCEIY/",
        "https://booking.naver.com/booking/12/bizes/1711626",
        9L,
        "myeongdong",
        "두두두 서울",
        "서울 중구 을지로 지하 42",
        new BigDecimal("37.5660510"),
        new BigDecimal("126.9823729"),
        PlaceKind.POPUP_VENUE);
  }

  /** 화면 계약 1장의 콘서트 예시다. 시작 시각과 공연장을 갖는 유일한 종류라 (EV-10) 따로 만든다. */
  private static EventSummary concert() {
    return new EventSummary(
        118L,
        "mock_con_1",
        EventKind.CONCERT,
        SubjectType.IDOL,
        Trust.PARSED,
        "샘플아이돌 하린",
        "샘플아이돌 하린 단독 콘서트",
        LocalDate.of(2026, 9, 19),
        LocalDate.of(2026, 9, 19),
        null,
        LocalTime.of(19, 0),
        null,
        null,
        null,
        "https://example.com/sample/mock_con_1",
        null,
        5L,
        "jamsil",
        "샘플 아레나",
        "서울 송파구 올림픽로 424",
        new BigDecimal("37.5209000"),
        new BigDecimal("127.1268000"),
        PlaceKind.CONCERT_HALL);
  }

  @DisplayName("행사 목록을 조회하면 items·nextCursor·hasNext 를 준다.")
  @Test
  void getEvents() throws Exception {
    // given
    EventCursor cursor = EventCursor.of(LocalDate.of(2026, 10, 1), 1L);
    given(eventQueryService.findEvents(any()))
        .willReturn(new EventSlice(List.of(summary()), cursor, true));

    // when & then
    mockMvc
        .perform(get("/api/v1/events"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isArray())
        .andExpect(jsonPath("$.items[0].externalId").value("pg_8709"))
        .andExpect(jsonPath("$.nextCursor").value(cursor.encode()))
        .andExpect(jsonPath("$.hasNext").value(true));
  }

  @DisplayName("마지막 페이지에서는 nextCursor 가 null 이고 hasNext 가 false 다.")
  @Test
  void getEvents_atLastPage() throws Exception {
    // given
    given(eventQueryService.findEvents(any()))
        .willReturn(new EventSlice(List.of(summary()), null, false));

    // when & then — 키를 지우지 않고 null 로 내보낸다 (API 컨벤션). jsonPath 의
    // doesNotExist 는 JSON null 도 통과시켜서 그것으로는 이 차이를 못 본다.
    mockMvc
        .perform(get("/api/v1/events"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("\"nextCursor\":null")))
        .andExpect(jsonPath("$.hasNext").value(false));
  }

  @DisplayName("행사 장소는 중첩 객체로 나간다.")
  @Test
  void getEvents_nestsPlace() throws Exception {
    // given
    given(eventQueryService.findEvents(any()))
        .willReturn(new EventSlice(List.of(summary()), null, false));

    // when & then
    mockMvc
        .perform(get("/api/v1/events"))
        .andExpect(jsonPath("$.items[0].place.name").value("두두두 서울"))
        .andExpect(jsonPath("$.items[0].place.kind").value("POPUP_VENUE"))
        .andExpect(jsonPath("$.items[0].place.lat").value(37.5660510));
  }

  @DisplayName("행사 장소의 district 는 지역 코드 문자열이다.")
  @Test
  void getEvents_hasDistrict() throws Exception {
    // given
    given(eventQueryService.findEvents(any()))
        .willReturn(new EventSlice(List.of(summary()), null, false));

    // when & then — 프론트가 60곳에서 쓰는 값이라 번호가 아니라 코드여야 한다
    mockMvc
        .perform(get("/api/v1/events"))
        .andExpect(jsonPath("$.items[0].place.district").value("myeongdong"));
  }

  @DisplayName("상세 시트가 쓰는 특전·참여조건·예약링크가 목록에 실린다.")
  @Test
  void getEvents_hasDetailFields() throws Exception {
    // given
    given(eventQueryService.findEvents(any()))
        .willReturn(new EventSlice(List.of(summary()), null, false));

    // when & then — 프론트에 상세 조회 호출이 없어 목록 배열로 상세를 그린다
    mockMvc
        .perform(get("/api/v1/events"))
        .andExpect(jsonPath("$.items[0].perks").value("MD 구매 특전 포토카드 1장 랜덤 증정"))
        .andExpect(jsonPath("$.items[0].conditions").value("성인 인증 필요"))
        .andExpect(
            jsonPath("$.items[0].reservationUrl")
                .value("https://booking.naver.com/booking/12/bizes/1711626"));
  }

  @DisplayName("굿즈는 목록에 실리지 않는다.")
  @Test
  void getEvents_omitsGoods() throws Exception {
    // given
    given(eventQueryService.findEvents(any()))
        .willReturn(new EventSlice(List.of(summary()), null, false));

    // when & then — 지연 컬렉션이라 목록에서 건드리면 N+1 이다
    mockMvc.perform(get("/api/v1/events")).andExpect(jsonPath("$.items[0].goods").doesNotExist());
  }

  @DisplayName("콘서트 시작 시각은 HH:mm 으로 나간다.")
  @Test
  void getEvents_formatsStartsAt() throws Exception {
    // given
    given(eventQueryService.findEvents(any()))
        .willReturn(new EventSlice(List.of(summary()), null, false));

    // when & then
    mockMvc
        .perform(get("/api/v1/events"))
        .andExpect(jsonPath("$.items[0].startsAt").value("19:30"));
  }

  @DisplayName("인증 없이 행사 목록을 조회해도 200 이다.")
  @Test
  void getEvents_withoutAuthentication() throws Exception {
    // given — Authorization 헤더를 붙이지 않는다 (API 설계 2-3 · PUBLIC)
    given(eventQueryService.findEvents(any())).willReturn(new EventSlice(List.of(), null, false));

    // when & then
    mockMvc.perform(get("/api/v1/events")).andExpect(status().isOk());
  }

  @DisplayName("망가진 커서로 조회하면 400 이다.")
  @Test
  void getEvents_hasBrokenCursor() throws Exception {
    mockMvc
        .perform(get("/api/v1/events").param("cursor", "!!!not-a-cursor!!!"))
        .andExpect(status().isBadRequest());
  }

  @DisplayName("외부 식별자로 행사 상세를 조회하면 200 이다.")
  @Test
  void getEvent() throws Exception {
    // given — 경로 변수는 숫자 PK 가 아니라 외부 식별자다 (API 설계 2-3)
    given(eventQueryService.findEvent("pg_8709")).willReturn(summary());

    // when & then
    mockMvc
        .perform(get("/api/v1/events/pg_8709"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.externalId").value("pg_8709"))
        .andExpect(jsonPath("$.id").value(1));
  }

  @DisplayName("행사 상세의 장소는 중첩 객체로 나간다.")
  @Test
  void getEvent_nestsPlace() throws Exception {
    // given
    given(eventQueryService.findEvent("pg_8709")).willReturn(summary());

    // when & then — 목록 항목과 같은 모양이다. 프론트가 상세를 목록 배열로도 그린다
    mockMvc
        .perform(get("/api/v1/events/pg_8709"))
        .andExpect(jsonPath("$.place.name").value("두두두 서울"))
        .andExpect(jsonPath("$.place.district").value("myeongdong"))
        .andExpect(jsonPath("$.place.kind").value("POPUP_VENUE"));
  }

  @DisplayName("콘서트 상세의 시작 시각은 HH:mm 으로 나간다.")
  @Test
  void getEvent_formatsStartsAt() throws Exception {
    // given
    given(eventQueryService.findEvent("mock_con_1")).willReturn(concert());

    // when & then — EV-10 의 검증 기준이다
    mockMvc
        .perform(get("/api/v1/events/mock_con_1"))
        .andExpect(jsonPath("$.kind").value("CONCERT"))
        .andExpect(jsonPath("$.place.kind").value("CONCERT_HALL"))
        .andExpect(jsonPath("$.startsAt").value("19:00"));
  }

  @DisplayName("굿즈는 상세에도 실리지 않는다.")
  @Test
  void getEvent_omitsGoods() throws Exception {
    // given
    given(eventQueryService.findEvent("pg_8709")).willReturn(summary());

    // when & then — 단건이라 N+1 은 아니지만, 수집한 것이 전부 빈 배열이라
    // 화면에 나오는 것이 없다 (화면 계약 「안 보내도 되는 것」). 목록과 모양을 가르지 않는다
    mockMvc.perform(get("/api/v1/events/pg_8709")).andExpect(jsonPath("$.goods").doesNotExist());
  }

  @DisplayName("없는 행사를 조회하면 404 와 EVENT_NOT_FOUND 다.")
  @Test
  void getEvent_eventIsNotFound() throws Exception {
    // given
    given(eventQueryService.findEvent("pg_0000"))
        .willThrow(new BusinessException(EventErrorCode.EVENT_NOT_FOUND));

    // when & then
    mockMvc
        .perform(get("/api/v1/events/pg_0000"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("EVENT_NOT_FOUND"));
  }

  @DisplayName("인증 없이 행사 상세를 조회해도 200 이다.")
  @Test
  void getEvent_withoutAuthentication() throws Exception {
    // given — Authorization 헤더를 붙이지 않는다 (API 설계 2-3 · PUBLIC)
    given(eventQueryService.findEvent("pg_8709")).willReturn(summary());

    // when & then
    mockMvc.perform(get("/api/v1/events/pg_8709")).andExpect(status().isOk());
  }
}
