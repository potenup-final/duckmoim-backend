package com.duckmoim.identity.presentation;

import static com.duckmoim.companion.CommentFixture.aComment;
import static com.duckmoim.companion.CompanionPostFixture.aCompanionPost;
import static com.duckmoim.identity.UserFixture.aUser;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.duckmoim.auth.domain.AuthUser;
import com.duckmoim.auth.domain.TokenProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * <b>AU-11 의 검증 기준 그 자체</b> — 「탈퇴 후 댓글 자리표시자 전환」.
 *
 * <p>이 검증은 컨텍스트를 가로지른다. 탈퇴는 {@code identity} 가 하고 작성자 블록은 {@code companion} 이 그리므로, 어느 한쪽의 컨트롤러
 * 테스트에 넣으면 <b>그 테스트가 남의 엔드포인트를 단언하게 된다.</b> 그래서 이 파일로 뗐다.
 *
 * <p><b>진짜 DB 로 돈다.</b> 익명화가 조립기를 고쳐서 되는 것이 아니라 <b>조인해 온 값이 비어서</b> 되는 일이라, 서비스를 목으로 세우면 검증되는 것이
 * 없다.
 *
 * <p>「자리표시자」의 뜻은 위키가 정했다 — <i>"본문만 빠지고 아바타 · 닉네임 · 작성시각은 그대로 내려간다"</i> (API 설계 2-5 · CM-08). 그것은
 * {@code Comment.status} 가 만드는 것이고, <b>탈퇴는 댓글 상태를 건드리지 않고 작성자 블록만 익명으로 바꾼다.</b>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("탈퇴 후 남는 것과 비는 것")
class WithdrawalTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private TokenProvider tokenProvider;
  @Autowired private JdbcTemplate jdbcTemplate;

  /** AU-11 의 검증 기준. 댓글은 남고 작성자만 익명이 된다. */
  @Test
  @DisplayName("탈퇴하면 내가 쓴 댓글의 작성자 블록이 빈다.")
  void withdraw_emptiesCommentAuthor() throws Exception {
    long userId = aUser().nickname("떠나는덕후").profile("소개", "/avatar/mine.webp").insert(jdbcTemplate);
    long postId = aCompanionPost().title("탈퇴 검증용 모집글").hostId(userId).insert(jdbcTemplate);
    long commentId = aComment().postId(postId).authorId(userId).insert(jdbcTemplate);

    mockMvc
        .perform(get("/api/v1/posts/" + postId + "/comments"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].author.nickname").value("떠나는덕후"))
        .andExpect(jsonPath("$.items[0].author.profileImageUrl").value("/avatar/mine.webp"));

    mockMvc.perform(delete("/api/v1/users/me").headers(bearer(userId))).andExpect(status().isOk());

    mockMvc
        .perform(get("/api/v1/posts/" + postId + "/comments"))
        .andExpect(status().isOk())
        // 댓글은 사라지지 않는다 — 탈퇴는 Comment.status 를 건드리지 않는다
        .andExpect(jsonPath("$.items[0].id").value(commentId))
        .andExpect(jsonPath("$.items[0].author.id").value(userId))
        .andExpect(jsonPath("$.items[0].author.nickname").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(
            jsonPath("$.items[0].author.profileImageUrl").value(org.hamcrest.Matchers.nullValue()));

    cleanUp(userId, postId, commentId);
  }

  /** 모집글 작성자 블록도 같은 값을 읽는다 — 조인해 온 닉네임이 비면 함께 빈다. */
  @Test
  @DisplayName("탈퇴하면 내가 쓴 모집글의 작성자 블록도 빈다.")
  void withdraw_emptiesPostAuthor() throws Exception {
    long userId = aUser().nickname("모집글쓴덕후").insert(jdbcTemplate);
    long postId = aCompanionPost().title("탈퇴 뒤에도 남는 모집글").hostId(userId).insert(jdbcTemplate);

    mockMvc.perform(delete("/api/v1/users/me").headers(bearer(userId))).andExpect(status().isOk());

    mockMvc
        .perform(get("/api/v1/posts/" + postId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.author.nickname").value(org.hamcrest.Matchers.nullValue()));

    cleanUp(userId, postId, null);
  }

  /** 접근 차단은 관문이 한다 — 탈퇴 상태를 매 요청 검사한다 (D 티켓). */
  @Test
  @DisplayName("탈퇴하면 손에 든 토큰으로 아무것도 할 수 없다.")
  void withdraw_closesEveryDoor() throws Exception {
    long userId = aUser().nickname("문닫는덕후").insert(jdbcTemplate);
    HttpHeaders headers = bearer(userId);

    mockMvc.perform(delete("/api/v1/users/me").headers(headers)).andExpect(status().isOk());

    mockMvc.perform(get("/api/v1/users/me").headers(headers)).andExpect(status().isUnauthorized());
    mockMvc.perform(get("/api/v1/users/" + userId)).andExpect(status().isNotFound());

    cleanUp(userId, null, null);
  }

  @Test
  @DisplayName("토큰 없이 탈퇴하면 401 이다.")
  void withdraw_withoutToken() throws Exception {
    mockMvc.perform(delete("/api/v1/users/me")).andExpect(status().isUnauthorized());
  }

  private HttpHeaders bearer(long userId) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(tokenProvider.createAccessToken(new AuthUser(userId, true, false)));
    return headers;
  }

  /** 넣은 행만 지운다. 시드를 비우면 뒤 테스트의 전제가 사라진다. */
  private void cleanUp(long userId, Long postId, Long commentId) {
    if (commentId != null) {
      jdbcTemplate.update("DELETE FROM comment WHERE id = ?", commentId);
    }
    if (postId != null) {
      jdbcTemplate.update("DELETE FROM companion_post WHERE id = ?", postId);
    }
    jdbcTemplate.update("DELETE FROM refresh_token WHERE user_id = ?", userId);
    jdbcTemplate.update("DELETE FROM user WHERE id = ?", userId);
  }
}
