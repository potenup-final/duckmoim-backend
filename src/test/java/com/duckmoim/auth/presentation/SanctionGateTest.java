package com.duckmoim.auth.presentation;

import static com.duckmoim.safety.SanctionFixture.aSanction;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
 * 제재 중 차단이 관문에서 나는지 — 쓰기(I-14)와 <b>비공개 읽기</b>(STAR-84).
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

  /**
   * 퇴장만 접두어 아래에서 열려 있다 (CH-04).
   *
   * <p>도메인-모델링.md 「3.3 경계를 넘는 불변식」이 <i>"제재가 막는 것은 새로 쓰는 일이지 관계를 끊는 일이 아니다"</i> 로 이 예외를 미리 적어 뒀다.
   * 신고와 탈퇴를 목록에서 뺀 것과 같은 판단이다.
   *
   * <p><b>{@code BANNED} 로 쏜다.</b> 셋 중 가장 센 제재라 여기서 열리면 나머지 둘도 열린다 — 반대로 가장 약한 것으로 보면 예외가 실제로 걸렸는지가
   * 아니라 제재가 약한 것인지가 섞인다.
   *
   * <p><b>없는 방 번호라 404 로 끝난다.</b> 관문이 막으면 403 이므로 둘이 갈린다 — 실제로 제외 목록을 비워 이 검사가 403 으로 뒤집히는 것을 확인했다.
   */
  @DisplayName("제재 중에도 채팅방을 나갈 수 있다.")
  @Test
  void allowsLeavingWhileSanctioned() throws Exception {
    sanction(SanctionKind.BANNED);

    mockMvc
        .perform(delete("/api/v1/chat-rooms/404404/members/me").headers(bearer()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("CHAT_ROOM_NOT_FOUND"));
  }

  /**
   * 등록 경로에 조회가 함께 걸린다. 메서드를 안 가리면 경고받은 사람이 남의 글도 못 본다.
   *
   * <p><b>채팅 경로가 여기서 빠졌다</b> (2026-09-11 · STAR-84). 그쪽은 전부 비공개라 {@code BANNED} 의 읽기를 막고, 아래에서 따로
   * 본다. 모집글·댓글은 <b>가장 센 제재로도 열려 있어야 한다</b> — 비회원에게 열린 경로라 막아도 로그아웃하면 그대로 보인다.
   */
  @DisplayName("제재 중에도 공개 읽기는 막지 않는다.")
  @ParameterizedTest
  @ValueSource(strings = {"/api/v1/posts", "/api/v1/posts/1/comments"})
  void allowsReading(String path) throws Exception {
    sanction(SanctionKind.BANNED);

    mockMvc
        .perform(get(path).headers(bearer()))
        .andExpect(
            result ->
                assertThat(result.getResponse().getStatus())
                    .isNotEqualTo(HttpStatus.FORBIDDEN.value()));
  }

  /**
   * 이 티켓의 본문이다 (STAR-84). 도메인 6장 제재 축 표의 「비공개 읽기」 열에서 {@code BANNED} 만 불가다.
   *
   * <p>막는 이유는 <b>제재당한 사람이 피해자와 같은 방을 계속 읽는 자리</b>이기 때문이다. 방에서 내보내는 수단이 따로 없어 여기가 유일한 차단점이다.
   *
   * <p><b>방 목록은 여기 없다.</b> 그것만 열어 두는 이유가 아래 {@link #allowsRoomListWhileBanned} 에 있다 — 대화가 막히는 자리는
   * 상세와 메시지이고, 목록은 나갈 방을 찾는 통로다.
   */
  @DisplayName("영구 정지 중에는 채팅방을 읽을 수 없다.")
  @ParameterizedTest
  @ValueSource(strings = {"/api/v1/chat-rooms/404404", "/api/v1/chat-rooms/404404/messages"})
  void blocksPrivateReadingWhenBanned(String path) throws Exception {
    sanction(SanctionKind.BANNED);

    mockMvc
        .perform(get(path).headers(bearer()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("USER_SANCTIONED"));
  }

  /**
   * <b>퇴장 예외가 닿을 수 있는 문인지 본다</b> (CH-04 · 리뷰 지적).
   *
   * <p>목록까지 막으면 {@code BANNED} 은 <b>나갈 방의 번호를 얻을 길이 없다</b> — {@code roomId} 를 담는 응답이 전부 이 접두어 아래이고,
   * 알림도 모집글·댓글 번호만 싣는다. 그러면 위 {@link #allowsLeavingWhileSanctioned} 는 <b>없는 번호를 쏘아 초록불인 채</b> 실제로는
   * 아무도 닿지 못하는 문을 지키게 된다.
   *
   * <p>열어도 되는 이유는 목록이 담는 것이 방 번호 · 모집글 번호 · 모집글 제목 · 만남시각 · 멤버 <b>수</b> 다섯뿐이라, 대화도 멤버 신원도 없기 때문이다.
   */
  @DisplayName("영구 정지 중에도 채팅방 목록은 볼 수 있다.")
  @Test
  void allowsRoomListWhileBanned() throws Exception {
    sanction(SanctionKind.BANNED);

    mockMvc
        .perform(get("/api/v1/chat-rooms").headers(bearer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray());
  }

  /**
   * <b>읽기 차단이 {@code BANNED} 에만 붙는지 본다.</b> 「제재 중이면 못 읽는다」로 짜면 이 셋이 함께 막히고, 그때 정지당한 사람이 자기 채팅방도 못
   * 보게 된다 — 쓰기 축과 읽기 축이 대칭이 아니다.
   *
   * <p>없는 방 번호라 통과하면 404 다. 관문이 막으면 403 이므로 둘이 갈린다.
   */
  @DisplayName("영구 정지가 아닌 제재는 채팅방 읽기를 막지 않는다.")
  @ParameterizedTest(name = "{0}")
  @EnumSource(
      value = SanctionKind.class,
      names = {"WARNED", "AGE_HOLD", "SUSPENDED"})
  void allowsPrivateReadingWhenNotBanned(SanctionKind kind) throws Exception {
    sanction(kind);

    mockMvc
        .perform(get("/api/v1/chat-rooms/404404").headers(bearer()))
        .andExpect(
            result ->
                assertThat(result.getResponse().getStatus())
                    .isNotEqualTo(HttpStatus.FORBIDDEN.value()));
  }

  /**
   * <b>이 경로가 막히면 이 티켓의 결정이 무너진다.</b> AU-12 의 검증 기준이 「정지 유저 로그인 시 안내와 사유 노출」이고, 그 안내가 이 응답으로 나간다
   * (API-설계.md 「2-2. 회원 (Identity)」). 읽기를 막는 김에 여기까지 걸면 당사자가 왜 막혔는지 영영 못 본다.
   */
  @DisplayName("영구 정지 중에도 내 정보와 제재 사유는 볼 수 있다.")
  @Test
  void allowsMyProfileWhileBanned() throws Exception {
    sanction(SanctionKind.BANNED);

    mockMvc
        .perform(get("/api/v1/users/me").headers(bearer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sanction.kind").value("BANNED"))
        .andExpect(jsonPath("$.sanction.reason").exists());
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
