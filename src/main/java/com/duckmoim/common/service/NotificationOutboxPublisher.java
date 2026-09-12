package com.duckmoim.common.service;

import com.duckmoim.common.domain.NotificationKind;
import com.duckmoim.common.domain.NotificationOutbox;
import com.duckmoim.common.domain.NotificationTarget;
import com.duckmoim.common.infra.NotificationOutboxRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 보낼 알림을 아웃박스에 적는다 (NT-01).
 *
 * <p><b>발송하지 않는다.</b> 도메인 트랜잭션 안에서 하는 일은 INSERT 하나이고, 집어서 보내는 것은 워커다 (NT-02). 그래서 알림 채널이 죽어 있어도 댓글
 * 작성이 실패하지 않는다 — {@code I-25} 가 요구하는 것이 그것이다 (도메인-모델링.md 「5. 불변식」).
 *
 * <p><b>부르는 쪽의 트랜잭션에서 돈다. {@code MANDATORY} 가 그것을 강제한다.</b> {@code AuditLogRecorder} 는 같은 계약을
 * javadoc 으로만 두었지만 여기서는 기계가 지키게 한다 — 트랜잭션 밖에서 부르면 행만 따로 커밋되어, 「댓글이 있으면 알림도 있다」 는 성질이 조용히 사라진다. 그
 * 구멍은 사라진 뒤에 알아채기 어렵다.
 *
 * <p><b>수신자를 부르는 쪽이 준다.</b> 여기서 계산하려면 Companion 을 알아야 하는데 {@code common} 이 컨텍스트를 참조하면 의존이 거꾸로 흐른다
 * (도메인-모델링.md 「2. 바운디드 컨텍스트」). 그래서 이 클래스는 ID 만 받는다.
 */
@Service
@RequiredArgsConstructor
public class NotificationOutboxPublisher {

  private final NotificationOutboxRepository notificationOutboxRepository;

  /**
   * 내 모집글에 댓글이 달렸다 (NT-06).
   *
   * @param hostId 모집글 방장. 알림을 받는 사람이다
   * @param authorId 댓글을 쓴 사람
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void postCommented(Long hostId, Long authorId, Long postId, Long commentId) {
    publish(
        NotificationKind.POST_COMMENTED,
        hostId,
        authorId,
        NotificationTarget.ofComment(postId, commentId));
  }

  /**
   * 내 댓글에 답글이 달렸다 (NT-06).
   *
   * @param parentAuthorId 부모 댓글을 쓴 사람. 알림을 받는 사람이다
   * @param authorId 답글을 쓴 사람
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void commentReplied(Long parentAuthorId, Long authorId, Long postId, Long commentId) {
    publish(
        NotificationKind.COMMENT_REPLIED,
        parentAuthorId,
        authorId,
        NotificationTarget.ofComment(postId, commentId));
  }

  /**
   * 채팅방에 새 메시지가 있다 (NT-06 · NT-07).
   *
   * <p><b>한 사람씩 부른다.</b> 수신자가 여럿이라 목록을 받는 편이 짧아 보이지만, 그러면 「누구를 뺄지」의 규칙이 여기와 부르는 쪽 둘로 갈린다 — 자기 자신은
   * 아래가 빼고 <b>지금 보고 있는 사람은 부르는 쪽이 뺀다</b> (NT-07). 한쪽만 보고 고치면 다른 쪽이 조용히 남는다.
   *
   * <p><b>보고 있는 사람을 여기서 못 거른다.</b> 「보고 있다」는 채팅의 사실이고, {@code common} 이 Chat 을 참조하면 의존이 거꾸로 흐른다
   * (도메인-모델링.md 「2. 바운디드 컨텍스트」). 수신자를 넣는 쪽이 정한다는 이 클래스의 규칙이 그대로 적용되는 자리다.
   *
   * @param recipientId 알림을 받는 사람. 방 멤버 중 보낸 사람과 보고 있는 사람을 뺀 나머지다
   * @param senderId 메시지를 보낸 사람
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void roomMessaged(Long recipientId, Long senderId, Long roomId, Long messageId) {
    publish(
        NotificationKind.ROOM_MESSAGED,
        recipientId,
        senderId,
        NotificationTarget.ofRoomMessage(roomId, messageId));
  }

  /**
   * <b>자기 행동으로 자기에게 알림을 만들지 않는다.</b> 명세에 없어 STAR-118 에서 정했다 — NT-06 이 알림을 넣은 이유로 든 것이 「방장이 댓글을 알
   * 방법이 없다」 인데, 자기가 쓴 댓글은 이미 알고 있다.
   *
   * <p>판정을 여기 두는 이유는 넣는 쪽이 둘이기 때문이다. 채팅 메시지가 붙으면서 실제로 둘이 됐고, 규칙을 양쪽에 두었으면 한쪽이 빠뜨렸을 것이다 — 채팅은 수신자가
   * 여럿이라 보낸 사람이 목록에 그냥 들어 있다.
   */
  private void publish(
      NotificationKind kind, Long recipientId, Long actorId, NotificationTarget target) {

    // 수신자가 없는 것과 자기 자신인 것을 섞지 않는다. null 은 아래 엔티티가 거른다.
    if (recipientId != null && recipientId.equals(actorId)) {
      return;
    }

    notificationOutboxRepository.save(NotificationOutbox.of(kind, recipientId, target));
  }
}
