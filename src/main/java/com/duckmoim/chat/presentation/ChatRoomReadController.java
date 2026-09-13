package com.duckmoim.chat.presentation;

import com.duckmoim.auth.domain.AuthUser;
import com.duckmoim.chat.service.ChatRoomReadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 읽음 표시 (CH-13).
 *
 * <p><b>목록 조회에 끼우지 않고 문을 따로 둔다.</b> {@code GET /messages} 가 읽음까지 처리하면 조회가 상태를 바꾼다 — 알림이 읽음
 * 처리(NT-09)와 배지 조회(NT-10)를 따로 둔 것과 같은 판단이다.
 *
 * <p><b>본문 없는 200 이다.</b> 돌려줄 것이 없다 — 배지는 방 목록(CH-05)이 함께 내린다. 여기서 안 읽은 수를 같이 돌려주면 읽음 처리가 배지 계산을
 * 겸하게 된다.
 *
 * <p><b>등급은 {@code SIGNUP_WRITE} 의 {@code /api/v1/chat-rooms/**} 가 덮는다</b> — {@code POST} 라 새로 더할
 * 줄이 없다. 제재 중에는 막힌다 ({@code SanctionGateConfig} 의 {@code SANCTIONED_PRIVATE} 가 방 아래를 메서드 구분 없이 막는다)
 * — STAR-84 가 「제재 중에는 읽기도 막는다」로 정한 선과 같다.
 */
@Tag(name = "채팅", description = "채팅방 읽음 표시")
@RestController
@RequestMapping("/api/v1/chat-rooms/{roomId}/read")
@RequiredArgsConstructor
public class ChatRoomReadController {

  private final ChatRoomReadService chatRoomReadService;

  /**
   * 그 번호까지 읽은 것으로 적는다 (CH-13).
   *
   * <p><b>읽은 지점은 뒤로 가지 않는다.</b> 이미 그보다 앞서 있으면 아무 일도 하지 않고 200 이다 — 위로 스크롤해 옛 메시지를 보다가 그 지점을 보내도 배지가
   * 되살아나지 않는다.
   *
   * <p>방 멤버 판정은 service 가 한다. 관문은 {@code SIGNUP} 까지만 본다.
   */
  @Operation(
      summary = "읽음 표시",
      description =
          "그 번호까지 읽은 것으로 적는다. 방 멤버만 부를 수 있고 나간 사람은 403 이다. "
              + "읽은 지점은 앞으로만 가며, 이미 앞서 있으면 아무 일도 하지 않고 200 이다. 본문 없이 200 을 준다.")
  @PostMapping
  public void markRead(
      @PathVariable Long roomId,
      @AuthenticationPrincipal AuthUser authUser,
      @Valid @RequestBody ChatRoomReadRequest request) {

    chatRoomReadService.markRead(roomId, authUser.userId(), request.lastReadMessageId());
  }
}
