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
 * <p><b>{@code lastMessage} 는 아직 없다.</b> {@code unreadCount} 는 CH-13 이 더했다 — 무엇을 세는지는 {@code
 * ChatRoomSummary} 에 적었다.
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
        summary.memberCount(),
        summary.unreadCount());
  }
}
