package com.duckmoim.chat.presentation;

import com.duckmoim.auth.domain.AuthUser;
import com.duckmoim.chat.service.ChatRoomDetailQueryService;
import com.duckmoim.chat.service.ChatRoomListQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 채팅방 목록·상세 (CH-05 · CH-06).
 *
 * <p><b>{@code SIGNUP} 등급이다.</b> 조회 경로는 보통 가입 미완료 상태에서도 열지만(API-설계.md 2-2), 가입을 마치지 않은 계정은 애초에 방
 * 멤버가 될 수 없다 — 초대 대상이 되려면 댓글을 써야 하고(CH-02) 댓글 작성 자체가 {@code SIGNUP} 이다. {@code users/me/posts} 를
 * {@code SIGNUP} 으로 둔 것과 같은 근거다.
 */
@Tag(name = "채팅", description = "채팅방 목록·상세")
@RestController
@RequestMapping("/api/v1/chat-rooms")
@RequiredArgsConstructor
public class ChatRoomController {

  private final ChatRoomListQueryService chatRoomListQueryService;
  private final ChatRoomDetailQueryService chatRoomDetailQueryService;

  /**
   * 내가 속한 방 목록을 조회한다 (CH-05).
   *
   * <p><b>커서가 없다.</b> 한 사람이 속한 방 수가 작아 페이지네이션의 이득보다 화면 쪽 구현 비용이 크다.
   */
  @Operation(summary = "채팅방 목록 조회", description = "내가 속한 방만, 만남시각 임박순으로 전체를 반환한다.")
  @GetMapping
  public List<ChatRoomSummaryResponse> getRooms(@AuthenticationPrincipal AuthUser authUser) {
    return chatRoomListQueryService.findRooms(authUser.userId()).stream()
        .map(ChatRoomSummaryResponse::from)
        .toList();
  }

  /**
   * 방 상세를 조회한다 (CH-06).
   *
   * <p>멤버가 아니면 403 이다. 존재를 숨기려면 404 를 쓰는 일반 원칙의 예외다 — 명세가 403 을 명시했다.
   */
  @Operation(summary = "채팅방 상세 조회", description = "모집글 요약 · 멤버 목록 · 채팅 가능 여부를 함께 준다. 멤버가 아니면 403.")
  @GetMapping("/{roomId}")
  public ChatRoomDetailResponse getRoom(
      @PathVariable Long roomId, @AuthenticationPrincipal AuthUser authUser) {
    return ChatRoomDetailResponse.from(
        chatRoomDetailQueryService.findRoom(roomId, authUser.userId()));
  }
}
