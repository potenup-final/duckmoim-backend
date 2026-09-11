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

  private static final String SELECT_SUMMARY =
      """
      SELECT new com.duckmoim.chat.infra.ChatRoomSummary(
                 r.id, r.postId, p.title, p.meetAt,
                 (SELECT COUNT(m2)
                    FROM ChatRoomMember m2
                   WHERE m2.room = r
                     AND m2.leftAt IS NULL))
        FROM ChatRoom r
        JOIN CompanionPost p ON p.id = r.postId
       WHERE EXISTS (SELECT 1
                       FROM ChatRoomMember m
                      WHERE m.room = r
                        AND m.userId = :userId
                        AND m.leftAt IS NULL)
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
