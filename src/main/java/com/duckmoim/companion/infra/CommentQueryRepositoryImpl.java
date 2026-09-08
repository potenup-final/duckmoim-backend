package com.duckmoim.companion.infra;

import com.duckmoim.companion.domain.CommentCursor;
import com.duckmoim.companion.domain.CommentListQuery;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import java.util.List;

/**
 * JPQL 로 쓴 이유 — {@code Comment} 에 {@code User} 로 가는 연관이 없다. 애그리게이트끼리 ID 로만 참조하기 때문이다 (도메인 3.2).
 * Criteria API 는 매핑된 연관이 있어야 조인을 걸 수 있어서, 연관 없는 엔티티 조인({@code JOIN User u ON ...})을 쓰려면 JPQL 이 맞다.
 * {@code EventQueryRepositoryImpl} 이 Criteria 인 것은 그쪽이 선택 필터 넷을 조합하기 때문이고, 여기는 선택 조건이 커서 하나다.
 */
public class CommentQueryRepositoryImpl implements CommentQueryRepository {

  private static final String SELECT_AUTHORED =
      """
      SELECT new com.duckmoim.companion.infra.AuthoredComment(
                 c, u.nickname, u.profileImageUrl, u.lastSeenAt)
        FROM Comment c
        JOIN User u ON u.id = c.authorId
      """;

  /** 튜플 비교를 풀어 쓴 것이다. createdAt 이 같을 때 id 가 순서를 정한다 (CM-07). */
  private static final String AFTER_CURSOR =
      """
         AND (c.createdAt > :cursorCreatedAt
              OR (c.createdAt = :cursorCreatedAt AND c.id > :cursorId))
      """;

  @PersistenceContext private EntityManager entityManager;

  @Override
  public List<AuthoredComment> findRootSlice(CommentListQuery query) {
    String jpql =
        SELECT_AUTHORED
            + " WHERE c.postId = :postId AND c.parentId IS NULL"
            + (query.hasCursor() ? AFTER_CURSOR : "")
            // 인덱스·커서 키와 같은 순서여야 한다. 어긋나면 페이지 경계에서 누락이 생긴다.
            + " ORDER BY c.createdAt ASC, c.id ASC";

    TypedQuery<AuthoredComment> typed =
        entityManager
            .createQuery(jpql, AuthoredComment.class)
            .setParameter("postId", query.postId());

    if (query.hasCursor()) {
      CommentCursor cursor = query.cursor();
      typed
          .setParameter("cursorCreatedAt", cursor.createdAt())
          .setParameter("cursorId", cursor.id());
    }

    // OFFSET 을 쓰지 않는다. 한 건을 더 읽어 다음 페이지 유무를 판정한다.
    return typed.setMaxResults(query.size() + 1).getResultList();
  }

  @Override
  public List<AuthoredComment> findRepliesOf(Long postId, List<Long> parentIds) {
    if (parentIds.isEmpty()) {
      // IN () 는 문법 오류다. 루트가 없으면 대댓글도 없다.
      return List.of();
    }

    return entityManager
        .createQuery(
            SELECT_AUTHORED
                + " WHERE c.postId = :postId AND c.parentId IN :parentIds"
                // 인덱스 (post_id, parent_id, created_at, id) 와 같은 순서다.
                + " ORDER BY c.parentId ASC, c.createdAt ASC, c.id ASC",
            AuthoredComment.class)
        .setParameter("postId", postId)
        .setParameter("parentIds", parentIds)
        .getResultList();
  }
}
