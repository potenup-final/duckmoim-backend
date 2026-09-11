package com.duckmoim.chat.presentation;

import com.duckmoim.chat.service.ChatRoomSummaryView;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 방 목록 한 줄 (CH-05).
 *
 * <p><b>{@code lastMessage} · {@code unreadCount} 가 없다.</b> {@code Message}(CH-07)와 안 읽음 커서(CH-13)가
 * 아직 없어 채울 값이 없다.
 *
 * @param memberCount 지금 방에 있는 인원. 모집글 정원(`capacity`)과 무관하다 — CH-03 이 「모집글의 정원은 상한으로 쓰지 않는다」로 둘을
 *     분리했다
 */
public record ChatRoomSummaryResponse(
    Long roomId, Long postId, String postTitle, OffsetDateTime meetAt, long memberCount) {

  private static final ZoneId KST = ZoneId.of("Asia/Seoul");

  static ChatRoomSummaryResponse from(ChatRoomSummaryView view) {
    return new ChatRoomSummaryResponse(
        view.roomId(), view.postId(), view.postTitle(), toKst(view.meetAt()), view.memberCount());
  }

  private static OffsetDateTime toKst(LocalDateTime storedInUtc) {
    return storedInUtc.atOffset(ZoneOffset.UTC).atZoneSameInstant(KST).toOffsetDateTime();
  }
}
