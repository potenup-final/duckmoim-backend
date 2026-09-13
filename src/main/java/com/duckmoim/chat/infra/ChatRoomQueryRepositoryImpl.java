package com.duckmoim.chat.infra;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;

/**
 * JPQL 로 쓴 이유 — {@code ChatRoom} 에 {@code CompanionPost} 로 가는 연관이 없다. 애그리게이트끼리 ID 로만 참조하기 때문이다
 * (도메인-모델링.md 「3.2 애그리게이트 간 참조 규칙」). {@code CompanionPostQueryRepositoryImpl} 이 같은 이유로 JPQL 이다.
 *
 * <p><b>멤버 수는 서브쿼리로 센다.</b> 멤버 컬렉션을 함께 읽으면 방 수만큼 {@code chat_room_member} 를 통째로 끌어오게 되는데, 여기 필요한 것은
 * 개수뿐이다.
 */
public class ChatRoomQueryRepositoryImpl implements ChatRoomQueryRepository {

  /**
   * 방 목록 (CH-05 · CH-13 · CH-18).
   *
   * <p><b>{@code EXISTS} 에서 {@code JOIN} 으로 바꿨다</b> (CH-13). 안 읽은 수를 세려면 <b>내 멤버 행의 읽은 지점</b>이
   * 필요한데, {@code EXISTS} 는 있는지만 답하고 값을 꺼내 주지 않는다. 멤버 행은 방마다 하나뿐이라 조인해도 행이 늘지 않는다.
   *
   * <p><b>나간 방이 빠지는 것은 그 조인의 {@code leftAt IS NULL} 이다</b> (CH-18). 바꾸기 전의 {@code EXISTS} 가 하던 일을
   * 그대로 조인 조건이 한다.
   *
   * <p><b>안 읽은 수에서 내가 보낸 것을 뺀다.</b> 빼지 않으면 말할 때마다 자기 배지가 오른다. 「전송할 때 읽은 지점을 함께 민다」로도 풀 수 있지만, 그러면
   * 전송이 멤버 행까지 쓰게 되어 CH-07 이 지킨 성질(전송은 방을 건드리지 않는다)에 멤버 행이 딸려 들어온다.
   *
   * <p><b>지우거나 가린 메시지도 센다.</b> 말풍선이 자리표시자로 남기 때문이다 (CH-12 · AD-09) — 화면에 줄이 보이는데 배지가 안 오르면 검증 기준
   * 「실제와 일치」가 깨진다.
   *
   * <p><b>읽은 적이 없으면 방의 메시지를 전부 센다</b> — {@code lastReadMessageId} 가 {@code null} 인 경우다.
   */
  private static final String SELECT_SUMMARY =
      """
      SELECT new com.duckmoim.chat.infra.ChatRoomSummary(
                 r.id, r.postId, p.title, p.meetAt,
                 (SELECT COUNT(m2)
                    FROM ChatRoomMember m2
                   WHERE m2.room = r
                     AND m2.leftAt IS NULL),
                 (SELECT COUNT(msg)
                    FROM Message msg
                   WHERE msg.roomId = r.id
                     AND msg.senderId <> :userId
                     AND (me.lastReadMessageId IS NULL
                          OR msg.id > me.lastReadMessageId)))
        FROM ChatRoom r
        JOIN CompanionPost p ON p.id = r.postId
        JOIN ChatRoomMember me ON me.room = r
                              AND me.userId = :userId
                              AND me.leftAt IS NULL
       ORDER BY p.meetAt ASC, r.id ASC
      """;

  /**
   * 방장·초대 응답({@code ChatRoomInvitation})과 달리 여기는 {@code User} 를 조인한다 — 방 상세(CH-06)의 멤버 블록에 닉네임 ·
   * 아바타가 필요해서다 ({@code AuthoredPost} 와 같은 근거).
   */
  private static final String SELECT_MEMBER =
      """
      SELECT new com.duckmoim.chat.infra.AuthoredChatRoomMember(
                 m.userId, u.nickname, u.profileImageUrl, u.lastSeenAt, u.status)
        FROM ChatRoomMember m
        JOIN User u ON u.id = m.userId
       WHERE m.room.id = :roomId
         AND m.leftAt IS NULL
       ORDER BY m.joinedAt ASC
      """;

  @PersistenceContext private EntityManager entityManager;

  @Override
  public List<ChatRoomSummary> findSummariesForMember(Long userId) {
    return entityManager
        .createQuery(SELECT_SUMMARY, ChatRoomSummary.class)
        .setParameter("userId", userId)
        .getResultList();
  }

  @Override
  public List<AuthoredChatRoomMember> findMembersOf(Long roomId) {
    return entityManager
        .createQuery(SELECT_MEMBER, AuthoredChatRoomMember.class)
        .setParameter("roomId", roomId)
        .getResultList();
  }
}
