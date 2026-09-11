package com.duckmoim.notification.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.duckmoim.auth.domain.AuthUser;
import com.duckmoim.auth.domain.TokenProvider;
import com.duckmoim.auth.presentation.ImportSecurity;
import com.duckmoim.common.domain.NotificationKind;
import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.notification.domain.NotificationCursor;
import com.duckmoim.notification.domain.NotificationListQuery;
import com.duckmoim.notification.exception.NotificationErrorCode;
import com.duckmoim.notification.service.NotificationQueryService;
import com.duckmoim.notification.service.NotificationReadService;
import com.duckmoim.notification.service.NotificationSlice;
import com.duckmoim.notification.service.NotificationView;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 알림함의 HTTP 계약 (NT-08 · NT-09 · NT-10).
 *
 * <p>정렬과 커서 경계는 저장소·서비스 통합 테스트가 본다. 여기서는 <b>파라미터가 조회 조건으로 옮겨지는지</b>와 응답 모양만 본다.
 *
 * <p>등급 판정(익명 401 · 가입 미완료 403)도 여기 없다 — {@code EndpointGradeTest} 의 권한 표가 {@code GET
 * /api/v1/notifications} 행으로 이미 지킨다.
 */
@WebMvcTest(NotificationController.class)
@ImportSecurity
class NotificationControllerTest {

  private static final LocalDateTime CREATED_AT_UTC = LocalDateTime.of(2026, 9, 14, 1, 0);

  private static final long ME = 7L;

  @Autowired private MockMvc mockMvc;
  @Autowired private TokenProvider tokenProvider;

  @MockitoBean private NotificationQueryService notificationQueryService;
  @MockitoBean private NotificationReadService notificationReadService;

  @Captor private ArgumentCaptor<NotificationListQuery> query;

  @DisplayName("알림함을 조회하면 200 과 목록이 돌아온다.")
  @Test
  void getMyNotifications() throws Exception {
    given(notificationQueryService.findMyNotifications(any())).willReturn(onePage());

    mockMvc
        .perform(get("/api/v1/notifications").headers(bearer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].id").value(42))
        .andExpect(jsonPath("$.items[0].kind").value("POST_COMMENTED"))
        .andExpect(jsonPath("$.items[0].postId").value(10))
        .andExpect(jsonPath("$.items[0].commentId").value(100))
        .andExpect(jsonPath("$.items[0].read").value(false))
        .andExpect(jsonPath("$.hasNext").value(false))
        .andExpect(jsonPath("$.nextCursor").doesNotExist());
  }

  /** 알림 문구가 응답에 없다. 화면이 kind 와 참조 ID 로 조립한다 (API-설계.md 「2-10. 알림 (Notification) · 2차」). */
  @DisplayName("알림 문구는 응답에 실리지 않는다.")
  @Test
  void getMyNotifications_carriesNoMessage() throws Exception {
    given(notificationQueryService.findMyNotifications(any())).willReturn(onePage());

    mockMvc
        .perform(get("/api/v1/notifications").headers(bearer()))
        .andExpect(jsonPath("$.items[0].message").doesNotExist())
        .andExpect(jsonPath("$.items[0].title").doesNotExist());
  }

  /** 저장은 UTC 이고 응답은 KST 오프셋을 포함한다 (API-컨벤션.md 「필드 표기 규칙」). */
  @DisplayName("알림 시각은 KST 오프셋을 달고 나간다.")
  @Test
  void getMyNotifications_inKst() throws Exception {
    given(notificationQueryService.findMyNotifications(any())).willReturn(onePage());

    mockMvc
        .perform(get("/api/v1/notifications").headers(bearer()))
        .andExpect(jsonPath("$.items[0].createdAt").value("2026-09-14T10:00:00+09:00"));
  }

  /** 읽은 시각은 나가지 않는다. 화면이 쓰는 것은 「읽었나」 하나다. */
  @DisplayName("읽은 알림은 read 가 true 로 나간다.")
  @Test
  void getMyNotifications_isRead() throws Exception {
    given(notificationQueryService.findMyNotifications(any()))
        .willReturn(new NotificationSlice(List.of(view(true)), null, false));

    mockMvc
        .perform(get("/api/v1/notifications").headers(bearer()))
        .andExpect(jsonPath("$.items[0].read").value(true))
        .andExpect(jsonPath("$.items[0].readAt").doesNotExist());
  }

  @DisplayName("커서와 size 가 조회 조건으로 옮겨진다.")
  @Test
  void getMyNotifications_withCursor() throws Exception {
    given(notificationQueryService.findMyNotifications(any())).willReturn(emptyPage());
    String cursor = new NotificationCursor(CREATED_AT_UTC, 42L).encode();

    mockMvc
        .perform(
            get("/api/v1/notifications")
                .param("cursor", cursor)
                .param("size", "5")
                .headers(bearer()))
        .andExpect(status().isOk());

    then(notificationQueryService).should().findMyNotifications(query.capture());
    assertThat(query.getValue().cursor()).isEqualTo(new NotificationCursor(CREATED_AT_UTC, 42L));
    assertThat(query.getValue().size()).isEqualTo(5);
  }

  /** 수신자가 요청이 아니라 인증 주체에서 온다. 이것이 어긋나면 남의 알림함이 열린다 (I-24 · D-14). */
  @DisplayName("수신자는 요청이 아니라 토큰에서 온다.")
  @Test
  void getMyNotifications_takesRecipientFromToken() throws Exception {
    given(notificationQueryService.findMyNotifications(any())).willReturn(emptyPage());

    mockMvc
        .perform(get("/api/v1/notifications").param("recipientId", "999").headers(bearer()))
        .andExpect(status().isOk());

    then(notificationQueryService).should().findMyNotifications(query.capture());
    assertThat(query.getValue().recipientId()).isEqualTo(ME);
  }

  @DisplayName("커서가 없으면 첫 페이지를 읽는다.")
  @Test
  void getMyNotifications_withoutCursor() throws Exception {
    given(notificationQueryService.findMyNotifications(any())).willReturn(emptyPage());

    mockMvc.perform(get("/api/v1/notifications").headers(bearer())).andExpect(status().isOk());

    then(notificationQueryService).should().findMyNotifications(query.capture());
    assertThat(query.getValue().hasCursor()).isFalse();
    assertThat(query.getValue().size()).isEqualTo(NotificationListQuery.DEFAULT_SIZE);
  }

  @DisplayName("더 읽을 것이 있으면 nextCursor 가 실린다.")
  @Test
  void getMyNotifications_hasNext() throws Exception {
    NotificationCursor next = new NotificationCursor(CREATED_AT_UTC, 42L);
    given(notificationQueryService.findMyNotifications(any()))
        .willReturn(new NotificationSlice(List.of(view(false)), next, true));

    mockMvc
        .perform(get("/api/v1/notifications").headers(bearer()))
        .andExpect(jsonPath("$.hasNext").value(true))
        .andExpect(jsonPath("$.nextCursor").value(next.encode()));
  }

  @DisplayName("판독할 수 없는 커서는 INVALID_INPUT 400 이다.")
  @Test
  void getMyNotifications_withBrokenCursor() throws Exception {
    mockMvc
        .perform(get("/api/v1/notifications").param("cursor", "!!broken!!").headers(bearer()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
  }

  @DisplayName("알림을 읽으면 200 과 빈 본문이 돌아온다.")
  @Test
  void markRead() throws Exception {
    mockMvc
        .perform(post("/api/v1/notifications/42/read").headers(bearer()))
        .andExpect(status().isOk())
        .andExpect(content().string(""));

    // 수신자는 경로가 아니라 토큰에서 온다. 이것이 어긋나면 남의 알림이 읽힌다 (I-24)
    then(notificationReadService).should().markRead(ME, 42L);
  }

  /**
   * <b>403 이 아니라 404 다.</b> 남의 알림도 없는 알림과 같은 응답으로 나가야 「그 번호의 알림이 존재한다」가 새지 않는다 (API-설계.md 「5. 결정
   * 사항」 D-14 ②). 그래서 이 검사는 상태 코드만이 아니라 코드 이름까지 본다.
   */
  @DisplayName("없는 알림을 읽으면 NOTIFICATION_NOT_FOUND 404 다.")
  @Test
  void markRead_notificationIsMissing() throws Exception {
    willThrow(new BusinessException(NotificationErrorCode.NOTIFICATION_NOT_FOUND))
        .given(notificationReadService)
        .markRead(anyLong(), anyLong());

    mockMvc
        .perform(post("/api/v1/notifications/42/read").headers(bearer()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOTIFICATION_NOT_FOUND"));
  }

  @DisplayName("전체 읽음은 200 과 빈 본문이 돌아온다.")
  @Test
  void markAllRead() throws Exception {
    mockMvc
        .perform(post("/api/v1/notifications/read").headers(bearer()))
        .andExpect(status().isOk())
        .andExpect(content().string(""));

    then(notificationReadService).should().markAllRead(ME);
  }

  /** 경로 길이가 달라 개별 읽음과 부딪히지 않는다. 둘이 섞이면 전체 읽음 요청이 알림 하나만 읽는다. */
  @DisplayName("전체 읽음이 개별 읽음 경로로 흘러가지 않는다.")
  @Test
  void markAllRead_doesNotHitSingle() throws Exception {
    mockMvc.perform(post("/api/v1/notifications/read").headers(bearer()));

    then(notificationReadService).should(never()).markRead(anyLong(), anyLong());
  }

  @DisplayName("안 읽은 수는 숫자 하나로 나간다.")
  @Test
  void getUnreadCount() throws Exception {
    given(notificationReadService.countUnread(ME)).willReturn(3L);

    mockMvc
        .perform(get("/api/v1/notifications/unread-count").headers(bearer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.unreadCount").value(3));
  }

  private static NotificationSlice onePage() {
    return new NotificationSlice(List.of(view(false)), null, false);
  }

  private static NotificationSlice emptyPage() {
    return new NotificationSlice(List.of(), null, false);
  }

  private static NotificationView view(boolean read) {
    return new NotificationView(
        42L, NotificationKind.POST_COMMENTED, 10L, 100L, read, CREATED_AT_UTC);
  }

  private HttpHeaders bearer() {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(tokenProvider.createAccessToken(new AuthUser(ME, true, false)));
    return headers;
  }
}
