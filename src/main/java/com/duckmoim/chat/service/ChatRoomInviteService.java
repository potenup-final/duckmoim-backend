package com.duckmoim.chat.service;

import com.duckmoim.chat.domain.ChatRoom;
import com.duckmoim.chat.exception.ChatErrorCode;
import com.duckmoim.chat.infra.ChatRoomRepository;
import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.companion.domain.CommentStatus;
import com.duckmoim.companion.domain.CompanionPost;
import com.duckmoim.companion.infra.CommentRepository;
import com.duckmoim.companion.infra.CompanionPostRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 방장이 댓글 작성자를 채팅방에 들인다 (CH-02 · CH-02a · CH-03).
 *
 * <p><b>판정이 셋으로 갈려 있다.</b> 방장인지는 모집글이 알고, 댓글을 썼는지는 댓글이 알고, 이미 멤버인지·나갔는지·자리가 남았는지는 방이 안다. 앞의 둘은 방
 * 애그리게이트 밖이라 (도메인-모델링.md 「3.1 경계와 트랜잭션 범위」) 여기서 읽어 넘기고, 셋째는 {@link ChatRoom#invite} 안이다 — {@code
 * Comment.deleteBy} 가 {@code hostId} 를 인자로 받는 것과 같은 배치다.
 *
 * <p><b>Companion 저장소 둘을 읽는다.</b> 채팅이 모집글 위에 얹힌 기능이라 (CH-01 이 모집글 하나에 방 하나로 묶었다) 피할 수 없는 방향이고, 읽기만
 * 한다 — 한 트랜잭션에서 고치는 애그리게이트는 방 하나다.
 *
 * <p><b>순서가 검증 기준의 순서다.</b> 명세의 「방장 아닌 계정 403 · 댓글 안 쓴 유저 400 · 101번째 409」 를 그 차례로 지난다. 방을 먼저 찾는
 * 이유는 나머지 판정이 모두 방이 있어야 뜻을 갖기 때문이다.
 */
@Service
@RequiredArgsConstructor
public class ChatRoomInviteService {

  private final ChatRoomRepository chatRoomRepository;
  private final CompanionPostRepository companionPostRepository;
  private final CommentRepository commentRepository;

  /**
   * 초대한다. 수락 단계가 없어 돌아오는 시점에 이미 멤버다 (CH-02).
   *
   * <p><b>알림을 보내지 않는다.</b> 명세가 <i>"초대 알림도 보내지 않는다 — 방 목록에서 확인하거나 그 방에 첫 메시지가 올 때 안다"</i> 고 정했고
   * NT-06 이 알림 종류 셋에서 초대를 <b>의도적으로</b> 뺐다. 여기에 발행을 하나 붙이면 그 결정이 코드에서 조용히 뒤집힌다.
   *
   * <p><b>{@code CHAT_ROOM_NOT_FOUND} 가 없는 모집글도 덮는다.</b> 방은 모집글 하나에 하나라 (CH-01) 글이 없으면 방도 없고, 요청자에게
   * 두 경우의 차이가 없다. 다만 <b>글은 있는데 방이 없는 경우</b>가 남아 있다 — 배포 창에서 구버전이 받은 글이 그렇다 ({@code
   * ChatRoomRepository} 의 각주). CH-06 이 그 자리의 계약을 정하기 전까지 초대도 같은 404 로 답한다.
   */
  @Transactional
  public ChatRoomInvitation invite(Long postId, Long inviteeId, Long requesterId) {
    ChatRoom room =
        chatRoomRepository
            .findByPostId(postId)
            .orElseThrow(() -> new BusinessException(ChatErrorCode.CHAT_ROOM_NOT_FOUND));

    requireHost(postId, requesterId);
    requireCommenter(postId, inviteeId);

    room.invite(inviteeId);

    return ChatRoomInvitation.from(room);
  }

  /**
   * 방장 외에는 누구도 초대할 수 없다 (CH-02).
   *
   * <p>모집글이 마감됐는지는 보지 않는다. CH-08 이 채팅 가능 여부를 <i>"모집글 상태가 아니라 만남시각으로 판정한다"</i> 고 정했고, 마감된 글의 방에서도
   * 만남시각 + 7일까지는 대화가 이어진다 — 그 사이에 빠진 사람을 부르지 못할 이유가 없다.
   */
  private void requireHost(Long postId, Long requesterId) {
    CompanionPost post =
        companionPostRepository
            .findById(postId)
            .orElseThrow(() -> new BusinessException(ChatErrorCode.CHAT_ROOM_NOT_FOUND));

    if (!post.getHostId().equals(requesterId)) {
      throw new BusinessException(ChatErrorCode.CHAT_ROOM_NOT_HOST);
    }
  }

  /**
   * 댓글을 쓴 사람만 초대 대상이다 (CH-02).
   *
   * <p><b>400 이고 403 이 아니다.</b> 명세의 검증 기준이 그렇게 못박았고, 뜻도 그쪽이 맞다 — 요청자에게 없는 것은 권한이 아니라 자격 있는 대상이다.
   * 권한이 없는 쪽은 방장이 아닌 요청자이고 그것이 위의 403 이다.
   *
   * <p><b>회원이 실재하는지 따로 보지 않는다.</b> 없는 회원번호는 그 글에 댓글이 있을 수 없어 여기서 걸린다.
   */
  private void requireCommenter(Long postId, Long inviteeId) {
    if (!commentRepository.existsByPostIdAndAuthorIdAndStatus(
        postId, inviteeId, CommentStatus.ACTIVE)) {
      throw new BusinessException(ChatErrorCode.CHAT_INVITEE_NOT_COMMENTER);
    }
  }
}
