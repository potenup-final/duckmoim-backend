package com.duckmoim.companion.presentation;

import static com.duckmoim.companion.CompanionPostFixture.aCompanionPost;
import static com.duckmoim.identity.UserFixture.aUser;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.duckmoim.auth.domain.AuthUser;
import com.duckmoim.auth.domain.TokenProvider;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 유저가 쓴 모집글 두 경로의 <b>HTTP 계약</b> (AU-09 · AU-10).
 *
 * <p>정렬 · 커서 경계 · 댓글 수는 저장소와 서비스 테스트가 본다. 여기서 보는 것은 <b>카드 모양 · 등급 · 봉투</b>다.
 *
 * <p><b>진짜 DB 로 돈다.</b> 두 경로가 같은 카드를 내는지를 봐야 하고, 그것은 서비스를 목으로 세우면 <b>내가 목에 넣은 값을 내가 확인하는 것</b>이 된다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("유저가 쓴 모집글 엔드포인트")
class UserPostControllerTest {

  private static final LocalDateTime BASE = LocalDateTime.of(2026, 9, 1, 0, 0);

  @Autowired private MockMvc mockMvc;
  @Autowired private TokenProvider tokenProvider;
  @Autowired private JdbcTemplate jdbcTemplate;

  /** 카드 모양이 목록(PO-08)과 같아야 프론트가 파서를 하나만 쓴다. */
  @Test
  @DisplayName("내 모집글 내역은 목록과 같은 카드 모양으로 온다.")
  void getMyPosts() throws Exception {
    long userId = aUser().nickname("내글쓴덕후").insert(jdbcTemplate);
    long postId = insertPost(userId, "내가 쓴 모집글", BASE);

    mockMvc
        .perform(get("/api/v1/users/me/posts").headers(bearer(userId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].id").value(postId))
        .andExpect(jsonPath("$.items[0].title").value("내가 쓴 모집글"))
        .andExpect(jsonPath("$.items[0].status").value("OPEN"))
        .andExpect(jsonPath("$.items[0].meetPoint.place").exists())
        .andExpect(jsonPath("$.items[0].author.id").value(userId))
        .andExpect(jsonPath("$.items[0].author.nickname").value("내글쓴덕후"))
        .andExpect(jsonPath("$.items[0].commentCount").value(0))
        .andExpect(jsonPath("$.nextCursor").doesNotExist())
        .andExpect(jsonPath("$.hasNext").value(false))
        .andExpect(jsonPath("$.length()").value(3));

    cleanUp(userId, postId);
  }

  @Test
  @DisplayName("남의 모집글 내역도 같은 카드 모양으로 온다.")
  void getUserPosts() throws Exception {
    long userId = aUser().nickname("남의글덕후").insert(jdbcTemplate);
    long postId = insertPost(userId, "남이 쓴 모집글", BASE);

    mockMvc
        .perform(get("/api/v1/users/" + userId + "/posts"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].id").value(postId))
        .andExpect(jsonPath("$.items[0].author.id").value(userId))
        .andExpect(jsonPath("$.length()").value(3));

    cleanUp(userId, postId);
  }

  /** 만나기 전에 상대가 어떤 모집을 열었는지 보는 화면이라 로그인을 요구하면 그 확인이 막힌다 (등급 {@code PUBLIC}). */
  @Test
  @DisplayName("남의 모집글 내역은 토큰 없이 읽을 수 있다.")
  void getUserPosts_withoutToken() throws Exception {
    long userId = aUser().insert(jdbcTemplate);

    mockMvc.perform(get("/api/v1/users/" + userId + "/posts")).andExpect(status().isOk());

    cleanUp(userId, null);
  }

  @Test
  @DisplayName("토큰 없이 내 모집글 내역을 읽으면 401 이다.")
  void getMyPosts_withoutToken() throws Exception {
    mockMvc.perform(get("/api/v1/users/me/posts")).andExpect(status().isUnauthorized());
  }

  /** 없는 회원과 글이 없는 회원의 응답이 같다 — 존재 여부가 새지 않는다. 프로필 단건이 404 를 낸다. */
  @Test
  @DisplayName("없는 회원의 모집글 내역은 빈 페이지로 온다.")
  void getUserPosts_unknownUser() throws Exception {
    mockMvc
        .perform(get("/api/v1/users/9999999/posts"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isEmpty())
        .andExpect(jsonPath("$.hasNext").value(false));
  }

  @Test
  @DisplayName("커서가 어긋나면 400 이다.")
  void getUserPosts_malformedCursor() throws Exception {
    long userId = aUser().insert(jdbcTemplate);

    mockMvc
        .perform(get("/api/v1/users/" + userId + "/posts").param("cursor", "!!!"))
        .andExpect(status().isBadRequest());

    cleanUp(userId, null);
  }

  /**
   * <b>{@code /me/posts} 가 {@code /&#123;userId&#125;/posts} 에 먹히지 않는지 본다.</b>
   *
   * <p>스프링이 리터럴 경로를 변수 경로보다 먼저 골라 지금은 내 내역으로 간다. 우선순위가 뒤집히면 남의 내역이 열리는 것이 아니라 <b>{@code "me"} 를
   * {@code Long} 으로 바꾸다 터진다.</b> 등급도 다르다 ({@code SIGNUP} vs {@code PUBLIC}).
   */
  @Test
  @DisplayName("내 모집글 경로는 회원번호 경로에 먹히지 않는다.")
  void getMyPosts_isNotSwallowedByPathVariable() throws Exception {
    long mine = aUser().nickname("내경로덕후").insert(jdbcTemplate);
    long other = aUser().nickname("남경로덕후").insert(jdbcTemplate);
    long minePost = insertPost(mine, "내 글", BASE);
    long otherPost = insertPost(other, "남의 글", BASE);

    mockMvc
        .perform(get("/api/v1/users/me/posts").headers(bearer(mine)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].id").value(minePost));

    cleanUp(mine, minePost);
    cleanUp(other, otherPost);
  }

  @Test
  @DisplayName("회원번호가 숫자가 아니면 400 이다.")
  void getUserPosts_malformedUserId() throws Exception {
    mockMvc.perform(get("/api/v1/users/abc/posts")).andExpect(status().isBadRequest());
  }

  private long insertPost(long hostId, String title, LocalDateTime createdAt) {
    return aCompanionPost().title(title).hostId(hostId).createdAt(createdAt).insert(jdbcTemplate);
  }

  private HttpHeaders bearer(long userId) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(tokenProvider.createAccessToken(new AuthUser(userId, true, false)));
    return headers;
  }

  /** 넣은 행만 지운다. {@code V21} 시드를 비우면 뒤 테스트의 전제가 사라진다. */
  private void cleanUp(long userId, Long postId) {
    if (postId != null) {
      jdbcTemplate.update("DELETE FROM companion_post WHERE id = ?", postId);
    }
    jdbcTemplate.update("DELETE FROM refresh_token WHERE user_id = ?", userId);
    jdbcTemplate.update("DELETE FROM user WHERE id = ?", userId);
  }
}
