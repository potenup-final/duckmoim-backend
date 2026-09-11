package com.duckmoim.auth.presentation;

import static com.duckmoim.safety.SanctionFixture.aSanction;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.duckmoim.auth.domain.AuthUser;
import com.duckmoim.auth.domain.TokenProvider;
import com.duckmoim.safety.domain.SanctionKind;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * 제재 중 쓰기 차단이 관문에서 나는지 (I-14).
 *
 * <p>이 티켓의 완료 조건 한 줄이 그것이다 — <i>"제재 중 유저가 모집글·댓글을 쓰면 차단된다. <b>개별 엔드포인트가 아니라 인터셉터 한 곳에서</b> 판정"</i>.
 *
 * <p><b>진짜 요청으로 본다.</b> 서비스를 직접 부르면 「인터셉터가 걸려 있는지」가 검증에서 빠진다 — 판정이 서비스 안에 있어도 그 검사는 통과한다.
 *
 * <p><b>없는 자원을 향해 쏜다.</b> 막히면 403 이고 통과하면 404 다. 차단 여부만 보면 되므로 실제 모집글을 만들지 않는다 — 만들면 그 생성 경로가 이 검사의
 * 선행이 되고, 하필 그 경로가 이 인터셉터에 걸린다.
 *
 * <p>유저는 V11 시드의 2 번('댓글덕후')이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SanctionGateTest {

  private static final long USER_ID = 2L;

  @Autowired private MockMvc mockMvc;
  @Autowired private TokenProvider tokenProvider;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private Clock clock;

  /** 도메인 6장 제재 축 표의 「쓰기」 열이 막힘인 셋이다. */
  @DisplayName("제재 중 유저가 모집글을 쓰면 403 이다.")
  @ParameterizedTest(name = "{0}")
  @EnumSource(
      value = SanctionKind.class,
      names = {"AGE_HOLD", "SUSPENDED", "BANNED"})
  void blocksPostWriting(SanctionKind kind) throws Exception {
    sanction(kind);

    mockMvc
        .perform(
            post("/api/v1/posts")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .headers(bearer()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("USER_SANCTIONED"));
  }

  @DisplayName("제재 중 유저가 댓글을 쓰면 403 이다.")
  @Test
  void blocksCommentWriting() throws Exception {
    sanction(SanctionKind.SUSPENDED);

    mockMvc
        .perform(
            post("/api/v1/posts/1/comments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .headers(bearer()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("USER_SANCTIONED"));
  }

  /** 「사유는 본인에게 보여주는 정보라 노출해도 된다」 (API 설계 4장 · AD-04 · AU-12). */
  @DisplayName("403 의 message 에 제재 사유가 담긴다.")
  @Test
  void carriesReason() throws Exception {
    sanction(SanctionKind.SUSPENDED);

    mockMvc
        .perform(
            post("/api/v1/posts")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .headers(bearer()))
        .andExpect(jsonPath("$.message").value(containsString("픽스처 제재")));
  }

  /** 도메인 6장이 「막을 것이면 정지를 준다」고 적었다. 여기가 뒤집히면 경고가 정지가 된다. */
  @DisplayName("경고받은 유저의 쓰기는 막지 않는다.")
  @Test
  void allowsWarnedWriting() throws Exception {
    sanction(SanctionKind.WARNED);

    mockMvc
        .perform(
            post("/api/v1/posts")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .headers(bearer()))
        .andExpect(status().isBadRequest());
  }

  /**
   * 채팅 쓰기도 같은 관문에서 막힌다 (CH-20).
   *
   * <p>도메인 3.3 이 <i>"제재 중 유저의 채팅 쓰기 차단 | Safety → Chat | I-14 와 같은 정책 객체를 채팅 경로에도 건다"</i> 로 2차 항목을
   * 열어 뒀다. 판정은 그대로 {@code SanctionPolicy} 가 하므로, 이 검사가 보는 것은 <b>경로 등록이 실제로 걸렸는가</b> 하나다.
   *
   * <p><b>없는 방 번호로 쏜다</b> (CH-07 의 전송 경로). 막히면 403 이고, 통과하면 본문이 비어 400 으로 끝난다 — 모집글 쪽과 같은 모양이다.
   */
  @DisplayName("제재 중 유저가 채팅 메시지를 보내면 403 이다.")
  @ParameterizedTest(name = "{0}")
  @EnumSource(
      value = SanctionKind.class,
      names = {"AGE_HOLD", "SUSPENDED", "BANNED"})
  void blocksChatWriting(SanctionKind kind) throws Exception {
    sanction(kind);

    mockMvc
        .perform(
            post("/api/v1/chat-rooms/404404/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .headers(bearer()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("USER_SANCTIONED"));
  }

  /** 경고는 채팅에서도 막지 않는다. 도메인 6장 제재 축 표의 「쓰기」 열이 모든 경로에 같게 적용된다. */
  @DisplayName("경고받은 유저의 채팅 쓰기는 막지 않는다.")
  @Test
  void allowsWarnedChatWriting() throws Exception {
    sanction(SanctionKind.WARNED);

    mockMvc
        .perform(
            post("/api/v1/chat-rooms/404404/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .headers(bearer()))
        .andExpect(status().isBadRequest());
  }

  @DisplayName("제재가 없으면 채팅 쓰기가 관문에서 막히지 않는다.")
  @Test
  void allowsUnsanctionedChatWriting() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/chat-rooms/404404/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .headers(bearer()))
        .andExpect(status().isBadRequest());
  }

  /** 등록 경로에 조회가 함께 걸린다. 메서드를 안 가리면 경고받은 사람이 남의 글도 못 본다. */
  @DisplayName("제재 중에도 읽기는 막지 않는다.")
  @ParameterizedTest
  @ValueSource(
      strings = {
        "/api/v1/posts",
        "/api/v1/posts/1/comments",
        "/api/v1/chat-rooms",
        "/api/v1/chat-rooms/404404"
      })
  void allowsReading(String path) throws Exception {
    sanction(SanctionKind.BANNED);

    mockMvc
        .perform(get(path).headers(bearer()))
        .andExpect(
            result ->
                assertThat(result.getResponse().getStatus())
                    .isNotEqualTo(HttpStatus.FORBIDDEN.value()));
  }

  @DisplayName("제재가 없으면 쓰기가 관문에서 막히지 않는다.")
  @Test
  void allowsUnsanctionedWriting() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/posts")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .headers(bearer()))
        .andExpect(status().isBadRequest());
  }

  private void sanction(SanctionKind kind) {
    LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);

    aSanction()
        .userId(USER_ID)
        .kind(kind)
        .issuedAt(now.minusDays(1))
        .until(kind.hasUntil() ? now.plusDays(3) : null)
        .insert(jdbc);
  }

  private HttpHeaders bearer() {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(tokenProvider.createAccessToken(new AuthUser(USER_ID, true, false)));
    return headers;
  }
}
