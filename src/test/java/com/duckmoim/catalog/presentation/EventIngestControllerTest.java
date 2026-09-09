package com.duckmoim.catalog.presentation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.duckmoim.auth.presentation.ImportSecurity;
import com.duckmoim.catalog.service.EventIngestCommand;
import com.duckmoim.catalog.service.EventIngestResult;
import com.duckmoim.catalog.service.EventIngestService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 적재 요청의 HTTP 계약만 본다 (테스트 컨벤션 · 테스트 계층).
 *
 * <p>여기서 보는 것은 <b>요청을 service 까지 들여보내는지</b>다. 건수 계산과 멱등성은 실제 MySQL 로 도는 {@code
 * EventIngestServiceTest} 가 본다.
 */
@WebMvcTest(EventIngestController.class)
@ImportSecurity
@DisplayName("행사 벌크 적재 요청")
class EventIngestControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private EventIngestService eventIngestService;

  @Value("${duckmoim.ingest.key}")
  private String ingestKey;

  /**
   * 배치 안에 같은 외부 식별자가 둘 있는 요청이다.
   *
   * <p>service 로 들어가면 upsert 가 <b>기존 DB 에 있는지만</b> 보고 판정해 둘 다 새 행으로 만들고, {@code
   * uk_event_external_id} 위반이 캐치올로 떨어져 <b>500</b> 이 난다. 트랜잭션이 하나라 그 배치 전체가 롤백되고, 잡이 하루 한 번이라 다음 날까지
   * 갱신이 멈춘다. 실측했다 — {@code Duplicate entry 'probe_dup'} 이후 남은 행이 0 이었다.
   *
   * <p>그래서 관문에서 400 으로 되돌린다. 크롤러가 어느 데이터가 문제인지 알 수 있어야 한다.
   */
  @Test
  @DisplayName("한 배치에 같은 외부 식별자가 둘 있으면 400 이고 적재하지 않는다.")
  void rejectsDuplicateExternalIdInOneBatch() throws Exception {
    mockMvc
        .perform(bulkRequest(bodyOf("probe_dup", "probe_dup")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
        .andExpect(jsonPath("$.message").value("외부 식별자가 중복된 행사가 있습니다."));

    then(eventIngestService).should(never()).ingest(any());
  }

  /** 중복 검사가 정상 배치를 막지 않는지 본다 — 막으면 하루치 적재가 통째로 400 이 된다. */
  @Test
  @DisplayName("외부 식별자가 서로 다르면 적재한다.")
  void ingestsDistinctExternalIds() throws Exception {
    given(eventIngestService.ingest(any(EventIngestCommand.class)))
        .willReturn(new EventIngestResult(2L, 2, 0, 0L));

    mockMvc
        .perform(bulkRequest(bodyOf("probe_1", "probe_2")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.created").value(2));
  }

  private MockHttpServletRequestBuilder bulkRequest(String body) {
    return post("/api/v1/ingest/events/bulk")
        .header("X-Ingest-Key", ingestKey)
        .contentType(MediaType.APPLICATION_JSON)
        .content(body);
  }

  /** 필수 필드를 모두 채운 배치를 만든다. 외부 식별자만 인자로 갈린다 — 이 테스트가 보는 것이 그것뿐이다. */
  private static String bodyOf(String... externalIds) {
    StringBuilder events = new StringBuilder();

    for (String externalId : externalIds) {
      events.append(events.isEmpty() ? "" : ",").append(itemOf(externalId));
    }
    return "{\"events\":[" + events + "]}";
  }

  private static String itemOf(String externalId) {
    return """
        {
          "externalId": "%s",
          "source": "POPGA",
          "kind": "POPUP",
          "subjectType": "IDOL",
          "trust": "PARSED",
          "subject": "에스파",
          "startsOn": "2026-09-19",
          "endsOn": "2026-09-27",
          "sourceUrl": "https://example.com/sample/%s",
          "place": {
            "name": "두두두 서울",
            "address": "서울 중구 을지로 지하 42",
            "lat": 37.5660510,
            "lng": 126.9823729,
            "district": "myeongdong",
            "kind": "POPUP_VENUE"
          }
        }
        """
        .formatted(externalId, externalId);
  }
}
