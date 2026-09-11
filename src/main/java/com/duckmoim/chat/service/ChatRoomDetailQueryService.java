package com.duckmoim.chat.service;

import com.duckmoim.chat.domain.ChatRoom;
import com.duckmoim.chat.exception.ChatErrorCode;
import com.duckmoim.chat.infra.AuthoredChatRoomMember;
import com.duckmoim.chat.infra.ChatRoomRepository;
import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.companion.domain.CompanionPost;
import com.duckmoim.companion.infra.CompanionPostRepository;
import com.duckmoim.identity.domain.AuthorDisplay;
import java.time.Clock;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 방 상세 (CH-06).
 *
 * <p><b>순서가 검증 기준의 순서다.</b> 방이 없으면 404, 멤버가 아니면 403 — 방이 있어야 멤버 여부가 뜻을 갖는다.
 *
 * <p><b>모집글을 별도로 읽는다.</b> {@code ChatRoom} 이 만남시각을 갖지 않는다(도메인 3.1) — 방장을 갖지 않는 것과 같은 이유로, 애그리게이트 밖은
 * ID 로만 참조한다.
 */
@Service
@RequiredArgsConstructor
public class ChatRoomDetailQueryService {

  private final ChatRoomRepository chatRoomRepository;
  private final CompanionPostRepository companionPostRepository;
  private final Clock clock;

  @Transactional(readOnly = true)
  public ChatRoomDetailView findRoom(Long roomId, Long requesterId) {
    ChatRoom room =
        chatRoomRepository
            .findById(roomId)
            .orElseThrow(() -> new BusinessException(ChatErrorCode.CHAT_ROOM_NOT_FOUND));

    if (!room.isMember(requesterId)) {
      throw new BusinessException(ChatErrorCode.CHAT_ROOM_ACCESS_DENIED);
    }

    CompanionPost post =
        companionPostRepository
            .findById(room.getPostId())
            .orElseThrow(() -> new BusinessException(ChatErrorCode.CHAT_ROOM_NOT_FOUND));

    List<ChatRoomMemberView> members =
        chatRoomRepository.findMembersOf(roomId).stream().map(this::toMemberView).toList();

    return new ChatRoomDetailView(
        room.getId(),
        post.getId(),
        post.getTitle(),
        post.getMeetAt(),
        members,
        room.isWritable(post.getMeetAt(), clock));
  }

  private ChatRoomMemberView toMemberView(AuthoredChatRoomMember member) {
    AuthorDisplay display = member.display(clock);
    return new ChatRoomMemberView(
        member.userId(), display.nickname(), display.profileImageUrl(), display.lastSeen());
  }
}
