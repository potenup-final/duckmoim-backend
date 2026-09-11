package com.duckmoim.chat.presentation;

import com.duckmoim.chat.service.ChatRoomInvitation;
import io.swagger.v3.oas.annotations.media.Schema;

/** 초대 결과 (CH-02). 수락 단계가 없어 이 응답이 오면 이미 멤버다. */
public record ChatRoomInviteResponse(
    @Schema(description = "채팅방 번호", example = "3") Long roomId,
    @Schema(description = "초대 후 현재 멤버 수. 방장을 포함하고 상한은 100 이다", example = "4") int memberCount) {

  public static ChatRoomInviteResponse from(ChatRoomInvitation invitation) {
    return new ChatRoomInviteResponse(invitation.roomId(), invitation.memberCount());
  }
}
