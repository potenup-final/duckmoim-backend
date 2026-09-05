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
import com.duckmoim.catalog.service.EventQueryService;
import com.duckmoim.catalog.service.EventSlice;
import com.duckmoim.catalog.service.EventSummary;
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
}
