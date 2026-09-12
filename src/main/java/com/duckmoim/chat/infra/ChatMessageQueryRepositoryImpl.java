package com.duckmoim.chat.infra;

import com.duckmoim.chat.domain.MessageCursor;
import com.duckmoim.chat.domain.MessageListQuery;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import java.util.List;
import java.util.Optional;

/**
 * JPQL 로 쓴 이유는 다른 목록 조회들과 같다 — 커서 조건이 있을 때만 붙어서 정적 문자열 하나로 담기지 않는다.
 *
 * <p>{@code User} 를 조인하는 이유는 {@code ChatRoomQueryRepositoryImpl} 의 멤버 질의와 같다 — 말풍선에 닉네임 · 아바타가 필요하고
 * 애그리게이트끼리는 ID 로만 참조하므로 조회 시점에 붙인다.
 */
public class ChatMessageQueryRepositoryImpl implements ChatMessageQueryRepository {

  private static final String SELECT_IN_ROOM =
      """
      SELECT new com.duckmoim.chat.infra.AuthoredMessage(
                 m.id, m.roomId, m.senderId, u.nickname, u.profileImageUrl, u.lastSeenAt, u.status,
                 m.content, m.status, m.createdAt)
        FROM Message m
        JOIN User u ON u.id = m.senderId
       WHERE m.roomId = :roomId
      """;

  /**
   * 부등호가 {@code <} 인 것은 목록이 <b>최신순</b>이라 커서 <b>이전</b>으로 이어 읽기 때문이다 ({@link MessageCursor}).
   *
   * <p><b>튜플 비교를 풀어 쓸 것이 없다.</b> 다른 목록들은 {@code (정렬키, id)} 두 값이라 {@code OR} 로 두 줄이 되는데, 여기는 키가 하나라
   * 한 줄이다 — 커서를 하나로 둔 결정이 질의에서도 그대로 값을 한다.
   */
  private static final String BEFORE_CURSOR = "   AND m.id < :cursorId\n";

  /**
   * 지운 메시지를 거르지 않는다.
   *
   * <p>{@code status} 조건이 없는 것이 이 질의에서 <b>의도된 빈자리</b>다. CH-12 가 「자리표시자를 남긴다」로 정해서 지운 메시지도 목록에 그대로
   * 있어야 하고, 본문을 빼는 것은 응답을 조립하는 쪽의 일이다. 여기서 거르면 자리표시자가 사라진다.
   */
  private static final String ORDER_BY_NEWEST = " ORDER BY m.id DESC";

  /** 단건 조회 (CH-10). 위 목록과 같은 조인이라 같은 값이 나온다. */
  private static final String SELECT_ONE =
      SELECT_IN_ROOM.replace("WHERE m.roomId = :roomId", "WHERE m.id = :messageId");

  /**
   * 끊긴 지점부터 따라잡는 질의 (CH-11).
   *
   * <p><b>부등호와 정렬이 목록과 반대다.</b> 목록은 최신부터 거슬러 올라가고 이쪽은 앞으로 이어 읽는다. 같은 {@code (room_id, id)} 인덱스를 반대
   * 방향으로 훑는 것이라 추가 인덱스가 필요하지 않다 ({@code V703__chat_message_status_and_listing_index.sql}).
   */
  private static final String AFTER_CURSOR_OLDEST_FIRST =
      SELECT_IN_ROOM + "   AND m.id > :afterId\n" + " ORDER BY m.id ASC";

  @PersistenceContext private EntityManager entityManager;

  @Override
  public List<AuthoredMessage> findSlice(MessageListQuery query) {
    // 인덱스·커서 키와 같은 순서여야 한다. 어긋나면 페이지 경계에서 누락이 생긴다
    // (V703__chat_message_status_and_listing_index.sql).
    String jpql = SELECT_IN_ROOM + (query.hasCursor() ? BEFORE_CURSOR : "") + ORDER_BY_NEWEST;

    TypedQuery<AuthoredMessage> typed =
        entityManager
            .createQuery(jpql, AuthoredMessage.class)
            .setParameter("roomId", query.roomId());

    if (query.hasCursor()) {
      typed.setParameter("cursorId", query.cursor().id());
    }

    // OFFSET 을 쓰지 않는다 (CH-09 의 검증 기준). 한 건을 더 읽어 다음 페이지 유무를 판정한다.
    return typed.setMaxResults(query.size() + 1).getResultList();
  }

  @Override
  public List<AuthoredMessage> findAfter(Long roomId, Long afterMessageId, int limit) {
    return entityManager
        .createQuery(AFTER_CURSOR_OLDEST_FIRST, AuthoredMessage.class)
        .setParameter("roomId", roomId)
        .setParameter("afterId", afterMessageId)
        .setMaxResults(limit)
        .getResultList();
  }

  @Override
  public Optional<AuthoredMessage> findAuthoredById(Long messageId) {
    return entityManager
        .createQuery(SELECT_ONE, AuthoredMessage.class)
        .setParameter("messageId", messageId)
        .getResultList()
        .stream()
        .findFirst();
  }
}
