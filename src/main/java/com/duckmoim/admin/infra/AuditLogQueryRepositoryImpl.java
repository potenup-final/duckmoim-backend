package com.duckmoim.admin.infra;

import com.duckmoim.admin.domain.AuditLogCursor;
import com.duckmoim.admin.domain.AuditLogListQuery;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import java.util.List;

/**
 * JPQL 로 쓴 이유는 {@code CommentQueryRepositoryImpl} 과 같다 — {@code AuditLog} 에 {@code User} 로 가는 연관이
 * 없다. 애그리게이트끼리 ID 로만 참조하기 때문이다 (도메인-모델링.md 「3.2 애그리게이트 간 참조 규칙」). Criteria API 는 매핑된 연관이 있어야 조인을 걸
 * 수 있어서, 연관 없는 엔티티 조인을 쓰려면 JPQL 이 맞다.
 */
public class AuditLogQueryRepositoryImpl implements AuditLogQueryRepository {

  /**
   * 행위자를 {@code JOIN} 으로 붙인다. {@code LEFT JOIN} 이 아닌 이유 — 행위자는 관리자이고 관리자는 회원이므로 짝이 없는 기록이 생기지 않는다.
   * 짝이 없다면 그것은 표시할 이름이 없는 것이 아니라 데이터가 깨진 것이라, 조용히 null 로 내리는 것보다 목록에서 빠져 눈에 띄는 편이 낫다.
   */
  private static final String SELECT_ACTED_AUDIT_LOG =
      """
      SELECT new com.duckmoim.admin.infra.ActedAuditLog(a, u.nickname)
        FROM AuditLog a
        JOIN User u ON u.id = a.actorUserId
      """;

  /** 튜플 비교를 풀어 쓴 것이다. 최신순이라 커서 <b>이전</b>으로 이어 읽는다 (AuditLogCursor). */
  private static final String BEFORE_CURSOR =
      """
       WHERE (a.at < :cursorAt
              OR (a.at = :cursorAt AND a.id < :cursorId))
      """;

  @PersistenceContext private EntityManager entityManager;

  @Override
  public List<ActedAuditLog> findSlice(AuditLogListQuery query) {
    String jpql =
        SELECT_ACTED_AUDIT_LOG
            + (query.hasCursor() ? BEFORE_CURSOR : "")
            // 인덱스·커서 키와 같은 순서여야 한다. 어긋나면 페이지 경계에서 누락이 생긴다.
            + " ORDER BY a.at DESC, a.id DESC";

    TypedQuery<ActedAuditLog> typed = entityManager.createQuery(jpql, ActedAuditLog.class);

    if (query.hasCursor()) {
      AuditLogCursor cursor = query.cursor();
      typed.setParameter("cursorAt", cursor.at()).setParameter("cursorId", cursor.id());
    }

    // OFFSET 을 쓰지 않는다. 한 건을 더 읽어 다음 페이지 유무를 판정한다.
    return typed.setMaxResults(query.size() + 1).getResultList();
  }
}
