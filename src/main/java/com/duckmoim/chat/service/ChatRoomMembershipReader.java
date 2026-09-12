package com.duckmoim.chat.service;

import com.duckmoim.chat.domain.ChatRoom;
import com.duckmoim.chat.exception.ChatErrorCode;
import com.duckmoim.chat.infra.ChatRoomRepository;
import com.duckmoim.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 방 멤버 판정만 트랜잭션 안에서 한다 (CH-10).
 *
 * <p><b>빈을 나눈 이유는 트랜잭션 경계를 좁히기 위해서다</b> (PR 리뷰). {@code ChatStreamService#open} 에
 * {@code @Transactional} 을 걸면 그 메서드가 끝날 때까지 DB 커넥션을 쥐는데, 그 안에 Redis 구독이 들어 있다 — {@code
 * RedisMessageListenerContainer} 는 구독 등록을 <b>기본 2초까지</b> 기다린다 (바이트코드 확인: {@code
 * maxSubscriptionRegistrationWaitingTime = 2000L}).
 *
 * <pre>
 * Redis 장애
 *    스트림 열기 10개 × DB 커넥션 2초 점유
 *       → HikariCP 기본 풀 10 고갈
 *          → 로그인·모집글·댓글·알림까지 커넥션 대기
 * </pre>
 *
 * <p>자기 클래스의 메서드를 부르면 프록시를 지나지 않아 애너테이션이 안 걸린다. 그래서 별 빈이어야 한다 — {@code ChatMessageSendService} /
 * {@code ChatMessageWriter} 가 같은 이유로 나뉘어 있다.
 *
 * <p><b>판정 자체는 다른 경로들과 같다.</b> 목록 조회·삭제가 쓰는 {@code CHAT_ROOM_NOT_FOUND} · {@code
 * CHAT_ROOM_ACCESS_DENIED} 두 코드를 그대로 쓴다 — 같은 방에 대한 답이 경로마다 갈리면 그것이 더 이상하다.
 */
@Service
@RequiredArgsConstructor
public class ChatRoomMembershipReader {

  private final ChatRoomRepository chatRoomRepository;

  /**
   * 방이 있고 요청자가 그 방의 멤버인가 (I-18 · CH-18).
   *
   * <p>나간 사람은 {@code leftAt} 이 차 있어 {@code isMember} 가 멤버로 세지 않는다.
   */
  @Transactional(readOnly = true)
  public void requireMember(Long roomId, Long userId) {
    ChatRoom room =
        chatRoomRepository
            .findById(roomId)
            .orElseThrow(() -> new BusinessException(ChatErrorCode.CHAT_ROOM_NOT_FOUND));

    if (!room.isMember(userId)) {
      throw new BusinessException(ChatErrorCode.CHAT_ROOM_ACCESS_DENIED);
    }
  }
}
