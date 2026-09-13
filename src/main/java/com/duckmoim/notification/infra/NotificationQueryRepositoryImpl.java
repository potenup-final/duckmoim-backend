package com.duckmoim.notification.infra;

import com.duckmoim.notification.domain.Notification;
import com.duckmoim.notification.domain.NotificationCursor;
import com.duckmoim.notification.domain.NotificationListQuery;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import java.util.List;

/**
 * JPQL 로 쓴 이유는 다른 목록 조회들과 같다 — 커서 조건이 있을 때만 붙어서 정적 문자열 하나로 담기지 않는다.
 *
 * <p><b>수신자 조건이 이 클래스에서 가장 중요한 줄이다.</b> {@code I-24}(알림은 수신자 본인에게만 조회된다) 는 이중 방어가 없어, 이 {@code
 * WHERE} 절이 유일한 방어선이다 (도메인-모델링.md 「5. 불변식」). 그래서 조건을 선택으로 두지 않고 문자열에 박아 둔다 — 파라미터로 받는 조건은 안 넘길 수
 * 있지만 이건 뺄 수 없다.
 */
public class NotificationQueryRepositoryImpl implements NotificationQueryRepository {

  private static final String SELECT_MINE =
      """
      SELECT n FROM Notification n
       WHERE n.recipientId = :recipientId
      """;

  /**
   * 튜플 비교를 풀어 쓴 것이다.
   *
   * <p>부등호가 {@code <} 인 것은 알림함이 <b>최신순</b>이라 커서 <b>이전</b>으로 이어 읽기 때문이다 ({@link
   * NotificationCursor}).
   */
  private static final String BEFORE_CURSOR =
      """
         AND (n.createdAt < :cursorCreatedAt
              OR (n.createdAt = :cursorCreatedAt AND n.id < :cursorId))
      """;

  @PersistenceContext private EntityManager entityManager;

  @Override
  public List<Notification> findSlice(NotificationListQuery query) {
    String jpql =
        SELECT_MINE
            + (query.hasCursor() ? BEFORE_CURSOR : "")
            // 인덱스·커서 키와 같은 순서여야 한다. 어긋나면 페이지 경계에서 누락이 생긴다
            // (V804__index_notification_listing.sql).
            + " ORDER BY n.createdAt DESC, n.id DESC";

    TypedQuery<Notification> typed =
        entityManager
            .createQuery(jpql, Notification.class)
            .setParameter("recipientId", query.recipientId());

    if (query.hasCursor()) {
      NotificationCursor cursor = query.cursor();
      typed
          .setParameter("cursorCreatedAt", cursor.createdAt())
          .setParameter("cursorId", cursor.id());
    }

    // OFFSET 을 쓰지 않는다. 한 건을 더 읽어 다음 페이지 유무를 판정한다.
    return typed.setMaxResults(query.size() + 1).getResultList();
  }
}
