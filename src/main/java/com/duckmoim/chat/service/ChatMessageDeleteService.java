package com.duckmoim.chat.service;

import com.duckmoim.chat.domain.ChatRoom;
import com.duckmoim.chat.domain.Message;
import com.duckmoim.chat.exception.ChatErrorCode;
import com.duckmoim.chat.infra.ChatMessageRepository;
import com.duckmoim.chat.infra.ChatRoomRepository;
import com.duckmoim.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 메시지 삭제 (CH-12).
 *
 * <p><b>판정이 셋으로 갈려 있다.</b> 방 멤버인지는 방이 알고 (I-18), 보낸 사람인지와 이미 지웠는지는 메시지가 안다. 앞의 하나는 메시지 애그리게이트 밖이라
 * 여기서 읽어 판정하고, 뒤의 둘은 {@link Message#deleteBy} 안이다 — {@code ChatRoomInviteService} 가 방장 여부를 모집글에서 읽는
 * 것과 같은 배치다.
 *
 * <p><b>메시지가 그 방의 것인지도 본다.</b> 경로가 {@code /chat-rooms/{roomId}/messages/{messageId}} 라 두 값이 따로 오고,
 * 대조하지 않으면 <b>내가 멤버인 방의 번호를 붙여 남의 방 메시지를 지울 수 있다.</b> 지울 수 있는 것이 자기 메시지뿐이라 피해가 크지는 않지만, 방 멤버 판정이 아무
 * 일도 하지 않게 되는 것이 문제다.
 *
 * <p><b>본문을 지우지 않는다.</b> 조회에서 사라지는 것은 {@code ChatMessageQueryService} 가 {@code status} 를 보고 본문을 끊기
 * 때문이고, 본문이 남아야 신고(CH-21)와 관리자 열람(AD-08)이 판단 재료를 갖는다 — {@code Comment} 가 같은 이유로 같은 것을 한다.
 */
@Service
@RequiredArgsConstructor
public class ChatMessageDeleteService {

  private final ChatRoomRepository chatRoomRepository;
  private final ChatMessageRepository chatMessageRepository;

  /**
   * 지운다 (CH-12).
   *
   * <p><b>채팅 가능 구간을 보지 않는다</b> (CH-08). 그 구간은 <i>쓰기</i>를 막는 것이고 삭제는 이미 쓴 것을 거두는 일이다 — 만남 후 7일이 지났다고
   * 자기 말을 못 지우게 할 근거가 없고, 오히려 그때가 지우고 싶어지는 때다.
   */
  @Transactional
  public void delete(Long roomId, Long messageId, Long requesterId) {
    requireMember(roomId, requesterId);

    Message message =
        chatMessageRepository
            .findById(messageId)
            .orElseThrow(() -> new BusinessException(ChatErrorCode.CHAT_MESSAGE_NOT_FOUND));

    if (!message.getRoomId().equals(roomId)) {
      throw new BusinessException(ChatErrorCode.CHAT_MESSAGE_NOT_FOUND);
    }

    message.deleteBy(requesterId);
  }

  /** 방이 있고 요청자가 그 방의 멤버인가 (I-18 · CH-18). {@code ChatMessageQueryService} 와 같은 두 코드를 쓴다. */
  private void requireMember(Long roomId, Long requesterId) {
    ChatRoom room =
        chatRoomRepository
            .findById(roomId)
            .orElseThrow(() -> new BusinessException(ChatErrorCode.CHAT_ROOM_NOT_FOUND));

    if (!room.isMember(requesterId)) {
      throw new BusinessException(ChatErrorCode.CHAT_ROOM_ACCESS_DENIED);
    }
  }
}
