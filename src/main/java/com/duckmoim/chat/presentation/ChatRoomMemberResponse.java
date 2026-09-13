package com.duckmoim.chat.presentation;

import com.duckmoim.chat.service.ChatRoomMemberView;
import com.duckmoim.identity.domain.LastSeen;

/**
 * 방 상세의 멤버 블록 (CH-06).
 *
 * <p>탈퇴한 멤버는 {@code nickname} 이 자리표시자({@code "탈퇴한 회원"})이고 {@code profileImageUrl} · {@code
 * lastSeen} 은 {@code null} 이다 — {@code AuthorDisplay}(AU-11)가 이미 만든 값을 그대로 옮긴다.
 */
public record ChatRoomMemberResponse(
    Long userId, String nickname, String profileImageUrl, LastSeen lastSeen) {

  static ChatRoomMemberResponse from(ChatRoomMemberView view) {
    return new ChatRoomMemberResponse(
        view.userId(), view.nickname(), view.profileImageUrl(), view.lastSeen());
  }
}
