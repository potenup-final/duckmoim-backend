package com.duckmoim.chat.service;

import com.duckmoim.chat.domain.ChatRoom;
import com.duckmoim.chat.infra.ChatRoomRepository;
import com.duckmoim.companion.domain.CompanionPostOpened;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 모집글이 열리면 방을 연다 (CH-01).
 *
 * <p><b>모집글 작성과 같은 트랜잭션에서 돈다.</b> {@code MANDATORY} 가 그것을 강제한다 — 트랜잭션 없이 부르면 여기서 거절한다.
 *
 * <p>도메인-모델링.md 「3.2 애그리게이트 간 참조 규칙」이 <i>"한 트랜잭션에서 두 애그리게이트를 함께 수정하지 않는다"</i> 고 정했으므로 이것은 그 규칙의
 * 예외다. 그럼에도 이쪽을 고른 근거는 <b>CH-01a 의 존재 이유</b>다. 명세가 기존 모집글에 마이그레이션으로 방을 만드는 까닭을 <i>"조회 경로에 「방이 없는
 * 경우」 분기를 두지 않기 위해서"</i> 라고 적었는데, 커밋 뒤에 별도 트랜잭션으로 방을 만들면 그 분기가 사라지는 것이 아니라 <b>방금 만든 모집글에서 런타임에 다시
 * 생긴다.</b> 마이그레이션으로 과거를 메우면서 현재에 같은 구멍을 내는 셈이다.
 *
 * <p>3.2 가 막으려는 것은 잠금 경합과 트랜잭션 결합인데, 여기는 같은 DB 에 INSERT 두 건이고 <b>같은 행을 원하는 다른 주체가 없다.</b> 3.3 「경계를
 * 넘는 불변식」의 처리 방식 셋(정책 객체 · DB 제약 · 인터셉터)에도 「A 를 만들 때 B 를 만든다」에 해당하는 줄이 없어, 문서가 답을 주지 않는 자리다.
 *
 * <p><b>I-16 을 여기서 미리 세지 않는다.</b> 도메인-모델링.md 「5. 불변식」이 이중 방어를 유니크 제약으로 정했고, 사전 조회를 넣으면 동시 요청에서 어차피
 * 깨지는 검사가 하나 늘어난다.
 */
@Service
@RequiredArgsConstructor
public class ChatRoomOpenService {

  private final ChatRoomRepository chatRoomRepository;

  /**
   * 방장을 유일한 멤버로 두고 방을 연다 (CH-01).
   *
   * <p><b>{@code @EventListener} 이고 {@code @TransactionalEventListener} 가 아니다.</b> 후자는 커밋 전후로 시점을
   * 미루는 것이 목적인데, 여기서 원하는 것은 같은 트랜잭션 안에서 함께 커밋되는 것이다.
   */
  @EventListener
  @Transactional(propagation = Propagation.MANDATORY)
  public void openFor(CompanionPostOpened opened) {
    chatRoomRepository.save(ChatRoom.openFor(opened.postId(), opened.hostId()));
  }
}
