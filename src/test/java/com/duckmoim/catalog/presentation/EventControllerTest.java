package com.duckmoim.catalog.presentation;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
class EventControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private EventQueryService eventQueryService;

  private static EventSummary summary() {
    return new EventSummary(
        1L,
        "pg_8166",
        EventKind.POPUP,
        SubjectType.IDOL,
        Trust.PARSED,
        "아이브",
        "IVE 팝업",
        LocalDate.of(2026, 10, 1),
        LocalDate.of(2026, 10, 10),
        "매일 11:00 ~ 20:00",
        LocalTime.of(19, 30),
        "https://cdn.example.test/1.webp",
        "https://example.test/1",
        3L,
        "토리든 커넥트 성수",
        "서울 성동구 성수이로7가길 17",
        new BigDecimal("37.5418230"),
        new BigDecimal("127.0552876"),
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
        .andExpect(jsonPath("$.items[0].externalId").value("pg_8166"))
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
        .andExpect(jsonPath("$.items[0].place.name").value("토리든 커넥트 성수"))
        .andExpect(jsonPath("$.items[0].place.kind").value("POPUP_VENUE"))
        .andExpect(jsonPath("$.items[0].place.lat").value(37.5418230));
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
