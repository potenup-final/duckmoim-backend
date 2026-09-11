package com.duckmoim.common.service;

import com.duckmoim.common.domain.NotificationKind;
import com.duckmoim.common.domain.NotificationOutbox;
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
    publish(NotificationKind.POST_COMMENTED, hostId, authorId, postId, commentId);
  }

  /**
   * 내 댓글에 답글이 달렸다 (NT-06).
   *
   * @param parentAuthorId 부모 댓글을 쓴 사람. 알림을 받는 사람이다
   * @param authorId 답글을 쓴 사람
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void commentReplied(Long parentAuthorId, Long authorId, Long postId, Long commentId) {
    publish(NotificationKind.COMMENT_REPLIED, parentAuthorId, authorId, postId, commentId);
  }

  /**
   * <b>자기 행동으로 자기에게 알림을 만들지 않는다.</b> 명세에 없어 STAR-118 에서 정했다 — NT-06 이 알림을 넣은 이유로 든 것이 「방장이 댓글을 알
   * 방법이 없다」 인데, 자기가 쓴 댓글은 이미 알고 있다.
   *
   * <p>판정을 여기 두는 이유는 넣는 쪽이 둘이 되기 때문이다. 채팅 메시지가 붙을 때 같은 규칙을 다시 쓰게 하면 한쪽이 빠뜨린다.
   */
  private void publish(
      NotificationKind kind, Long recipientId, Long actorId, Long postId, Long commentId) {

    // 수신자가 없는 것과 자기 자신인 것을 섞지 않는다. null 은 아래 엔티티가 거른다.
    if (recipientId != null && recipientId.equals(actorId)) {
      return;
    }

    notificationOutboxRepository.save(NotificationOutbox.of(kind, recipientId, postId, commentId));
  }
}
