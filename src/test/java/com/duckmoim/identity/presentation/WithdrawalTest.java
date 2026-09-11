package com.duckmoim.identity.presentation;

import static com.duckmoim.companion.CommentFixture.aComment;
import static com.duckmoim.companion.CompanionPostFixture.aCompanionPost;
import static com.duckmoim.identity.UserFixture.aUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.duckmoim.auth.domain.AuthUser;
import com.duckmoim.auth.domain.TokenProvider;
import com.duckmoim.auth.service.AuthService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
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

  /** 실물 로그아웃을 그대로 쓰고, 롤백 테스트 하나에서만 실패를 심는다. */
  @MockitoSpyBean private AuthService authService;

  /**
   * AU-11 의 검증 기준. 댓글은 남고 작성자만 익명이 된다.
   *
   * <p><b>이 단언이 STAR-83 에서 뒤집혔다.</b> 그전에는 닉네임이 {@code null} 인 것을 고정하고 있었는데, 그것은 요구사항의 「익명화」가 아니라
   * <b>구현이 거기까지밖에 못 간 상태</b>였다 (명세서 「구현이 요구사항에 못 미친 것」). 화면에는 이름 없는 작성자로 떴다.
   */
  @Test
  @DisplayName("탈퇴하면 내가 쓴 댓글의 작성자가 자리표시자가 된다.")
  void withdraw_anonymizesCommentAuthor() throws Exception {
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
        .andExpect(jsonPath("$.items[0].author.nickname").value("탈퇴한 회원"))
        .andExpect(
            jsonPath("$.items[0].author.profileImageUrl").value(org.hamcrest.Matchers.nullValue()))
        // 남기면 「탈퇴한 회원 · 오늘 접속」 이 뜬다. last_seen_at 이 탈퇴 시점 값으로 남는다
        .andExpect(jsonPath("$.items[0].author.lastSeen").value(org.hamcrest.Matchers.nullValue()));

    cleanUp(userId, postId, commentId);
  }

  /** 모집글 작성자 블록도 같은 판정을 지난다 — 조립 지점이 갈라져 있으면 여기로만 실명이 샌다. */
  @Test
  @DisplayName("탈퇴하면 내가 쓴 모집글의 작성자도 자리표시자가 된다.")
  void withdraw_anonymizesPostAuthor() throws Exception {
    long userId = aUser().nickname("모집글쓴덕후").insert(jdbcTemplate);
    long postId = aCompanionPost().title("탈퇴 뒤에도 남는 모집글").hostId(userId).insert(jdbcTemplate);

    mockMvc.perform(delete("/api/v1/users/me").headers(bearer(userId))).andExpect(status().isOk());

    mockMvc
        .perform(get("/api/v1/posts/" + postId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.author.nickname").value("탈퇴한 회원"));

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

  /**
   * 프로필 단건과 그 사람의 글 목록이 어긋나 있던 자리다 (AU-11). 단건은 404 인데 목록은 계속 200 으로 글을 내려주고 있었다.
   *
   * <p><b>목록은 404 가 아니라 빈 페이지다.</b> 이 경로는 없는 회원번호에도 200 과 빈 페이지를 주므로, 탈퇴만 404 로 갈라 놓으면 탈퇴자와 없는 회원을
   * 구분해 주는 신호가 생긴다. 어긋나지 않는다는 것이 <b>같은 상태 코드</b>라는 뜻은 아니다.
   */
  @Test
  @DisplayName("탈퇴하면 그 사람의 모집글 목록이 빈 페이지가 된다.")
  void withdraw_closesUserPostList() throws Exception {
    long userId = aUser().nickname("목록닫는덕후").insert(jdbcTemplate);
    long postId = aCompanionPost().title("탈퇴 전에 쓴 모집글").hostId(userId).insert(jdbcTemplate);

    mockMvc
        .perform(get("/api/v1/users/" + userId + "/posts"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].id").value(postId));

    mockMvc.perform(delete("/api/v1/users/me").headers(bearer(userId))).andExpect(status().isOk());

    mockMvc
        .perform(get("/api/v1/users/" + userId + "/posts"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isEmpty());

    // 전체 목록에는 남는다 — 결정 D-3 이 모집글 삭제를 두지 않았다
    mockMvc.perform(get("/api/v1/posts/" + postId)).andExpect(status().isOk());

    cleanUp(userId, postId, null);
  }

  @Test
  @DisplayName("토큰 없이 탈퇴하면 401 이다.")
  void withdraw_withoutToken() throws Exception {
    mockMvc.perform(delete("/api/v1/users/me")).andExpect(status().isUnauthorized());
  }

  /**
   * <b>PR #84 리뷰가 지적한 자리다.</b> 탈퇴와 토큰 정리가 각자의 트랜잭션이면 앞이 커밋된 뒤 뒤가 실패해 「탈퇴는 됐는데 토큰이 남은」 상태로 굳고, 사용자가
   * 다시 부르면 이미 탈퇴한 계정이라 404 를 받아 <b>빠져나올 방법이 없다.</b>
   *
   * <p>한 트랜잭션으로 묶었으므로 <b>아무것도 일어나지 않은 상태로 돌아가야 한다.</b> 스파이라 다른 테스트는 실물 로그아웃을 그대로 쓴다.
   */
  @Test
  @DisplayName("토큰 정리가 실패하면 탈퇴도 함께 롤백된다.")
  void withdraw_rollsBackWhenLogoutFails() throws Exception {
    long userId = aUser().nickname("롤백덕후").insert(jdbcTemplate);
    doThrow(new IllegalStateException("토큰 정리 실패")).when(authService).logout(userId);

    mockMvc
        .perform(delete("/api/v1/users/me").headers(bearer(userId)))
        .andExpect(status().isInternalServerError());

    assertThat(statusOf(userId)).isEqualTo("ACTIVE");
    assertThat(nicknameOf(userId)).isEqualTo("롤백덕후");

    cleanUp(userId, null, null);
  }

  private String statusOf(long userId) {
    return jdbcTemplate.queryForObject(
        "SELECT status FROM user WHERE id = ?", String.class, userId);
  }

  private String nicknameOf(long userId) {
    return jdbcTemplate.queryForObject(
        "SELECT nickname FROM user WHERE id = ?", String.class, userId);
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
