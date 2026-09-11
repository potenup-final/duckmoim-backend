package com.duckmoim.chat.service;

import com.duckmoim.chat.domain.ChatRoom;

/**
 * 초대가 끝난 뒤의 방 (CH-02).
 *
 * <p><b>들어간 사람을 담지 않는다.</b> 부른 쪽이 방금 그 회원번호를 보냈으므로 되돌려 줄 것이 없고, 화면이 초대 직후 필요로 하는 것은 「몇 명이 됐는가」다
 * (CH-05 가 방 목록에 멤버 수를 보인다).
 *
 * <p>멤버 목록 자체는 방 상세가 준다 (CH-06). 여기서 함께 내보내면 같은 응답이 두 곳에서 관리된다.
 */
public record ChatRoomInvitation(Long roomId, int memberCount) {

  static ChatRoomInvitation from(ChatRoom room) {
    return new ChatRoomInvitation(room.getId(), room.currentMembers().size());
  }
}
