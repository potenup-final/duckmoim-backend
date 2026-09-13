package com.duckmoim.common.service;

import com.duckmoim.common.domain.NotificationKind;
import com.duckmoim.common.domain.NotificationOutbox;
import com.duckmoim.common.domain.NotificationTarget;
import com.duckmoim.common.infra.NotificationMuteRepository;
import com.duckmoim.common.infra.NotificationOutboxRepository;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
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
  private final NotificationMuteRepository notificationMuteRepository;

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
   * 채팅방에 새 메시지가 있다 (NT-06 · NT-07 · NT-11).
   *
   * <p><b>한 사람씩이 아니라 목록으로 받는다.</b> 수신 설정이 붙으면서 사람마다 한 번씩 물어보는 비용이 실제로 생겼다 — 6명 방의 메시지 한 건이 조회 다섯이
   * 되고, 그 자리는 <b>메시지 전송 트랜잭션 안</b>이다. 목록으로 받으면 한 번에 묻는다.
   *
   * <p><b>그래도 「누구를 뺄지」는 이 클래스가 쥔다.</b> 자기 자신도 끈 사람도 여기서 빠진다 — 규칙이 부르는 쪽으로 새면 넣는 쪽이 둘이라 한쪽이 빠뜨린다.
   *
   * <p><b>보고 있는 사람만은 부르는 쪽이 뺀다</b> (NT-07). 「보고 있다」는 채팅의 사실이고, {@code common} 이 Chat 을 참조하면 의존이 거꾸로
   * 흐른다 (도메인-모델링.md 「2. 바운디드 컨텍스트」).
   *
   * @param recipientIds 방의 현재 멤버에서 <b>지금 보고 있는 사람</b>을 뺀 나머지. 중복이 있어도 한 건만 쌓인다
   * @param senderId 메시지를 보낸 사람
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void roomMessaged(
      Collection<Long> recipientIds, Long senderId, Long roomId, Long messageId) {

    Set<Long> receiving = new LinkedHashSet<>(recipientIds);
    receiving.remove(senderId);

    if (receiving.isEmpty()) {
      return;
    }

    receiving.removeAll(
        notificationMuteRepository.findMutedUserIds(NotificationKind.ROOM_MESSAGED, receiving));

    NotificationTarget target = NotificationTarget.ofRoomMessage(roomId, messageId);
    receiving.forEach(
        recipientId ->
            notificationOutboxRepository.save(
                NotificationOutbox.of(NotificationKind.ROOM_MESSAGED, recipientId, target)));
  }

  /**
   * 댓글 알림 한 건을 쌓는다. <b>안 쌓는 경우가 둘이다.</b>
   *
   * <pre>
   * 자기 자신   자기가 쓴 댓글은 이미 알고 있다 (STAR-118)
   * 끈 종류     NT-11. 만들어 두고 거르는 것이 아니라 아예 안 만든다
   * </pre>
   *
   * <p><b>자기 자신을 빼는 판정이 여기 있는 이유는 넣는 쪽이 둘이기 때문이다.</b> 채팅 메시지가 붙으면서 실제로 둘이 됐고, 규칙을 양쪽에 두었으면 한쪽이 빠뜨렸을
   * 것이다 — 채팅은 수신자가 여럿이라 보낸 사람이 목록에 그냥 들어 있다.
   *
   * <p><b>끈 종류를 여기서 끊는 것은 NT-11 이 그렇게 정했기 때문이다.</b> 만들어 두고 목록에서 거르면 안 읽은 수(NT-10)가 화면과 어긋나고 30일
   * 만료(NT-11a)가 지울 것이 쌓인다.
   */
  private void publish(
      NotificationKind kind, Long recipientId, Long actorId, NotificationTarget target) {

    // 수신자가 없는 것은 섞지 않는다. null 은 아래 엔티티가 거른다 — 여기서 조용히 넘기면
    // 「수신자를 넣는 쪽이 정한다」를 어긴 호출이 아무 신호도 없이 사라진다.
    if (recipientId != null
        && (recipientId.equals(actorId)
            || notificationMuteRepository.existsByUserIdAndKind(recipientId, kind))) {
      return;
    }

    notificationOutboxRepository.save(NotificationOutbox.of(kind, recipientId, target));
  }
}
