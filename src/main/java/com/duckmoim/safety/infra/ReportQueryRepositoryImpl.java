package com.duckmoim.safety.infra;

import com.duckmoim.safety.domain.ReportCursor;
import com.duckmoim.safety.domain.ReportListQuery;
import com.duckmoim.safety.domain.ReportTargetType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import java.util.List;

/**
 * JPQL 로 쓴 이유는 {@code CommentQueryRepositoryImpl} · {@code AuditLogQueryRepositoryImpl} 과 같다 —
 * {@code Report} 에 대상으로 가는 연관이 없다. 애그리게이트끼리 ID 로만 참조하기 때문이고 (도메인-모델링.md 「3.2」), 대상이 셋이라 FK 자체를 걸 수
 * 없다 (V34).
 */
public class ReportQueryRepositoryImpl implements ReportQueryRepository {

  /**
   * 대상 표시명을 셋 중 하나에서 얻는다.
   *
   * <p><b>{@code LEFT JOIN} 이다.</b> 대상 행이 없어도 신고는 목록에 남아야 한다 (STAR-60) — 안쪽 조인으로 걸면 대상이 사라진 신고가 조용히
   * 큐에서 빠지고, 그것이 D-3 이 모집글에서 막으려던 구멍과 같다.
   *
   * <p><b>{@code COALESCE} 로 하나를 고른다.</b> 조인 조건에 {@code targetType} 이 걸려 있어 한 행에서 많아야 하나만 채워진다. 셋을
   * 따로 내리고 호출부가 고르게 하면 같은 판정이 두 곳에 생긴다.
   *
   * <p>신고자는 {@code JOIN} 이다. 신고자는 반드시 회원이라 짝이 없으면 데이터가 깨진 것이고, 조용히 null 로 내리는 것보다 눈에 띄는 편이 낫다 —
   * {@code AuditLogQueryRepositoryImpl} 이 행위자에 대해 같은 판단을 했다.
   */
  private static final String SELECT_REPORTED_TARGET =
      """
      SELECT new com.duckmoim.safety.infra.ReportedTarget(
                 r,
                 reporter.nickname,
                 COALESCE(targetUser.nickname, targetPost.title, commentAuthor.nickname),
                 targetComment.secret)
        FROM Report r
        JOIN User reporter ON reporter.id = r.reporterId
        LEFT JOIN User targetUser
             ON targetUser.id = r.targetId AND r.targetType = :userTarget
        LEFT JOIN CompanionPost targetPost
             ON targetPost.id = r.targetId AND r.targetType = :postTarget
        LEFT JOIN Comment targetComment
             ON targetComment.id = r.targetId AND r.targetType = :commentTarget
        LEFT JOIN User commentAuthor ON commentAuthor.id = targetComment.authorId
      """;

  private static final String WHERE_STATUS = " WHERE r.status = :status";

  /** 튜플 비교를 풀어 쓴 것이다. 최신순이라 커서 <b>이전</b>으로 이어 읽는다 (ReportCursor). */
  private static final String BEFORE_CURSOR =
      """
         (r.createdAt < :cursorCreatedAt
          OR (r.createdAt = :cursorCreatedAt AND r.id < :cursorId))
      """;

  @PersistenceContext private EntityManager entityManager;

  @Override
  public List<ReportedTarget> findSlice(ReportListQuery query) {
    String jpql = SELECT_REPORTED_TARGET + statusClause(query) + cursorClause(query);

    TypedQuery<ReportedTarget> typed =
        entityManager
            .createQuery(
                // 인덱스·커서 키와 같은 순서여야 한다. 어긋나면 페이지 경계에서 누락이 생긴다.
                jpql + " ORDER BY r.createdAt DESC, r.id DESC", ReportedTarget.class)
            .setParameter("userTarget", ReportTargetType.USER)
            .setParameter("postTarget", ReportTargetType.POST)
            .setParameter("commentTarget", ReportTargetType.COMMENT);

    if (query.hasStatus()) {
      typed.setParameter("status", query.status());
    }
    if (query.hasCursor()) {
      ReportCursor cursor = query.cursor();
      typed
          .setParameter("cursorCreatedAt", cursor.createdAt())
          .setParameter("cursorId", cursor.id());
    }

    // OFFSET 을 쓰지 않는다. 한 건을 더 읽어 다음 페이지 유무를 판정한다.
    return typed.setMaxResults(query.size() + 1).getResultList();
  }

  private static String statusClause(ReportListQuery query) {
    return query.hasStatus() ? WHERE_STATUS : "";
  }

  /** 조건이 둘 다 선택이라 앞에 WHERE 가 이미 있는지에 따라 접속사가 갈린다. */
  private static String cursorClause(ReportListQuery query) {
    if (!query.hasCursor()) {
      return "";
    }
    return (query.hasStatus() ? " AND" : " WHERE") + BEFORE_CURSOR;
  }
}
