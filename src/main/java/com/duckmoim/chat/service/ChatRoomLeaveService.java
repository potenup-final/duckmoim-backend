package com.duckmoim.chat.service;

import com.duckmoim.chat.domain.ChatRoom;
import com.duckmoim.chat.exception.ChatErrorCode;
import com.duckmoim.chat.infra.ChatRoomRepository;
import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.companion.domain.CompanionPost;
import com.duckmoim.companion.infra.CompanionPostRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 멤버가 스스로 채팅방을 나간다 (CH-04).
 *
 * <p><b>여기서 하는 일은 방장을 찾아 넘기는 것 하나다.</b> 판정 셋(방장 409 · 비멤버 403 · 이미 나간 사람 403)은 전부 {@link
 * ChatRoom#leave} 안이고, 방이 방장을 갖지 않아 (도메인-모델링.md 「3.1 경계와 트랜잭션 범위」) 그 값만 밖에서 읽어 온다 — {@link
 * ChatRoomInviteService} 가 방장과 댓글 작성자를 읽어 넘기는 것과 같은 배치다.
 *
 * <p><b>방 번호로 받는다.</b> 초대만 모집글 번호인 것은 그쪽 진입점이 모집글 상세의 댓글이라 그 화면에 방 번호가 없기 때문이고 (API-설계.md 「2-11. 채팅
 * (Chat) · 2차」), 나가기의 진입점은 방 화면이라 방 번호를 이미 쥐고 있다.
 *
 * <p><b>제재 중에도 나갈 수 있다.</b> 관문 쪽 판단이고 {@code SanctionGateConfig} 가 이 경로를 뺀다 — 제재가 막는 것은 새로 쓰는 일이지
 * 관계를 끊는 일이 아니다 (도메인-모델링.md 「3.3 경계를 넘는 불변식」). 그래서 여기에 제재 판정이 없는 것이 맞다.
 *
 * <p><b>모집글을 고치지 않는다.</b> 한 트랜잭션에서 바뀌는 애그리게이트는 방 하나이고 모집글은 방장을 알기 위해 읽기만 한다 — 나간 사람이 그 글의 댓글 작성자라는
 * 사실도 그대로 둔다. 퇴장은 방에서 나가는 일이지 댓글을 지우는 일이 아니다.
 */
@Service
@RequiredArgsConstructor
public class ChatRoomLeaveService {

  private final ChatRoomRepository chatRoomRepository;
  private final CompanionPostRepository companionPostRepository;

  /**
   * 나간다. 돌아오는 시점에 이미 멤버가 아니다 (CH-04).
   *
   * <p><b>알림을 보내지 않는다.</b> NT-06 의 알림 종류가 셋이고 (댓글 · 답글 · 새 메시지) 퇴장이 없다. 초대 알림을 의도적으로 뺀 것과 같은 줄이다.
   *
   * <p><b>방이 없는 모집글과 없는 방이 같은 404 다.</b> {@code ChatRoomRepository} 의 각주대로 배포 창에서 방 없이 저장된 모집글이 남을
   * 수 있는데, 그 글의 방 번호는 애초에 클라이언트가 쥘 수 없어 여기 도달하지 않는다. 도달한다면 없는 번호와 구분할 것이 없다.
   *
   * <p><b>모집글이 사라진 경우도 404 다.</b> 지금은 삭제가 없지만 (API-설계.md 「5. 결정 사항」 D-3) 방장을 못 읽으면 방장인지 아닌지를 판정할 수
   * 없어, 조용히 통과시키는 것보다 없는 방으로 답하는 편이 안전하다.
   */
  @Transactional
  public void leave(Long roomId, Long requesterId) {
    ChatRoom room =
        chatRoomRepository
            .findById(roomId)
            .orElseThrow(() -> new BusinessException(ChatErrorCode.CHAT_ROOM_NOT_FOUND));

    CompanionPost post =
        companionPostRepository
            .findById(room.getPostId())
            .orElseThrow(() -> new BusinessException(ChatErrorCode.CHAT_ROOM_NOT_FOUND));

    room.leave(requesterId, post.getHostId());
  }
}
