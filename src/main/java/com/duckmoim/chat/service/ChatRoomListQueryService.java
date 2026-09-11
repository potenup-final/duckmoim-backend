package com.duckmoim.chat.service;

import com.duckmoim.chat.infra.ChatRoomRepository;
import com.duckmoim.chat.infra.ChatRoomSummary;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 내가 속한 방 목록 (CH-05).
 *
 * <p><b>커서가 없다.</b> 한 사람이 속한 방 수가 작아 페이지네이션의 이득보다 화면 쪽 구현 비용이 크다 — 이 티켓 계획의 참고 사항으로 남겼다.
 *
 * <p><b>{@code lastMessage} · {@code unreadCount} 가 없다.</b> {@code Message}(CH-07)와 안 읽음 커서(CH-13)가
 * 아직 없어 채울 값이 없다. 그 티켓들이 이 서비스에 필드를 더한다.
 */
@Service
@RequiredArgsConstructor
public class ChatRoomListQueryService {

  private final ChatRoomRepository chatRoomRepository;

  @Transactional(readOnly = true)
  public List<ChatRoomSummaryView> findRooms(Long userId) {
    return chatRoomRepository.findSummariesForMember(userId).stream()
        .map(ChatRoomListQueryService::toView)
        .toList();
  }

  private static ChatRoomSummaryView toView(ChatRoomSummary summary) {
    return new ChatRoomSummaryView(
        summary.roomId(),
        summary.postId(),
        summary.postTitle(),
        summary.meetAt(),
        summary.memberCount());
  }
}
