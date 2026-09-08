package com.duckmoim.companion.infra;

import com.duckmoim.companion.domain.CommentCursor;
import com.duckmoim.companion.domain.CommentListQuery;
import com.duckmoim.companion.domain.CommentStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

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

  /**
   * 상태 조건이 없다. 관리자는 가시성 매트릭스 밖이고 (도메인-모델링.md 「7. 도메인 규칙」), 지운 댓글이야말로 신고를 판단할 재료다.
   *
   * <p>{@code getResultList} 로 받는 이유 — {@code getSingleResult} 는 없을 때 예외를 던진다. 없는 댓글은 404 이지 오류가
   * 아니라 판정을 호출부에 맡긴다.
   */
  @Override
  public Optional<AuthoredComment> findAuthoredById(Long commentId) {
    return entityManager
        .createQuery(SELECT_AUTHORED + " WHERE c.id = :commentId", AuthoredComment.class)
        .setParameter("commentId", commentId)
        .getResultList()
        .stream()
        .findFirst();
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

  /**
   * {@code ACTIVE} 만 센다. 그것이 CM-12 의 「비밀 포함 · 삭제·블라인드 제외 · 대댓글 포함」과 같은 조건이다 — 비밀은 {@code status} 가
   * 아니라 {@code secret} 플래그이고, 대댓글도 같은 표의 행이라 {@code parentId} 를 보지 않으면 함께 세어진다.
   */
  @Override
  public Map<Long, Long> countActiveByPostIds(List<Long> postIds) {
    if (postIds.isEmpty()) {
      // IN () 는 문법 오류다. 셀 모집글이 없으면 셀 것도 없다.
      return Map.of();
    }

    return entityManager
        .createQuery(
            """
            SELECT c.postId, count(c)
              FROM Comment c
             WHERE c.postId IN :postIds AND c.status = :active
             GROUP BY c.postId
            """,
            Object[].class)
        .setParameter("postIds", postIds)
        .setParameter("active", CommentStatus.ACTIVE)
        .getResultList()
        .stream()
        .collect(Collectors.toMap(row -> (Long) row[0], row -> (Long) row[1]));
  }
}
