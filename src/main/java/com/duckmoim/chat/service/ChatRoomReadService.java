package com.duckmoim.chat.service;

import com.duckmoim.chat.infra.ChatRoomMemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 어디까지 읽었는지를 적는다 (CH-13).
 *
 * <p><b>방 행을 잠그지 않는 것이 이 클래스의 요구사항이다.</b> 검증 기준이 「갱신이 방 행을 잠그지 않는다」이고, 도메인 3.1 이 <i>"여럿이 동시에 읽어도 서로
 * 기다리지 않아야 한다"</i> 로 근거를 적었다. 그래서 갱신이 <b>멤버 행 하나를 고치는 조건부 UPDATE</b> 한 문장이다.
 *
 * <p><b>멤버 판정은 방을 읽지만 잠그지는 않는다.</b> {@link ChatRoomMembershipReader} 가 평범한 조회라 서로 기다리게 만들지 않는다 —
 * 요구사항이 막은 것은 <b>잠금</b>이지 읽기가 아니다. 판정을 빼면 남의 방에 읽음 표시를 보낼 수 있고, 없는 방과 비멤버의 답이 다른 경로들과 갈린다.
 *
 * <p><b>보낸 번호는 그 방의 것이어야 한다.</b> 위조해도 남의 배지나 남의 대화에는 닿지 못하지만, <b>자기 배지가 한 번이 아니라 영구히 사라진다</b> — 갱신이
 * 앞으로만 가서 되돌릴 길이 없다 (PR 리뷰). 검사는 {@link ChatRoomMemberRepository#advanceLastRead} 의 {@code MAX} 조건이
 * 같은 문장 안에서 한다. 별도 조회로 두지 않는 이유는 왕복이 하나 늘고, 그 사이에 메시지가 들어오면 검사한 것과 미는 것이 어긋나기 때문이다.
 */
@Service
@RequiredArgsConstructor
public class ChatRoomReadService {

  private final ChatRoomMembershipReader chatRoomReader;
  private final ChatRoomMemberRepository chatRoomMemberRepository;

  /**
   * 그 번호까지 읽은 것으로 적는다.
   *
   * <p>이미 그보다 앞서 있으면 아무 일도 하지 않는다 — 실패가 아니다.
   *
   * @throws com.duckmoim.common.exception.BusinessException 방이 없으면 404, 멤버가 아니면 403
   */
  @Transactional
  public void markRead(Long roomId, Long userId, Long lastReadMessageId) {
    chatRoomReader.requireMember(roomId, userId);

    chatRoomMemberRepository.advanceLastRead(roomId, userId, lastReadMessageId);
  }
}
