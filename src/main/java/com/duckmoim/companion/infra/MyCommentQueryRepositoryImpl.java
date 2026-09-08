package com.duckmoim.companion.infra;

import com.duckmoim.companion.domain.CommentStatus;
import com.duckmoim.companion.domain.MyCommentCursor;
import com.duckmoim.companion.domain.MyCommentListQuery;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import java.util.List;

/**
 * JPQL 로 쓴 이유는 {@link CommentQueryRepositoryImpl} 과 같다 — {@code Comment} 에 {@code CompanionPost} 로
 * 가는 연관이 없다. 애그리게이트끼리 ID 로만 참조하기 때문이다 (도메인-모델링.md 「3.2 애그리게이트 간 참조 규칙」). Criteria API 는 매핑된 연관이 있어야
 * 조인을 걸 수 있다.
 */
public class MyCommentQueryRepositoryImpl implements MyCommentQueryRepository {

  private static final String SELECT_MY_COMMENT =
      """
      SELECT new com.duckmoim.companion.infra.MyComment(c, p.title)
        FROM Comment c
        JOIN CompanionPost p ON p.id = c.postId
       WHERE c.authorId = :authorId
         AND c.status = :status
      """;

  /**
   * 튜플 비교를 풀어 쓴 것이다. 부등호가 {@code <} 인 것이 목록 조회와 갈리는 지점이다 — 내 내역은 최신순이라 커서 <b>이전</b>으로 이어 읽는다
   * (MyCommentCursor).
   */
  private static final String BEFORE_CURSOR =
      """
         AND (c.createdAt < :cursorCreatedAt
              OR (c.createdAt = :cursorCreatedAt AND c.id < :cursorId))
      """;

  @PersistenceContext private EntityManager entityManager;

  @Override
  public List<MyComment> findMySlice(MyCommentListQuery query) {
    String jpql =
        SELECT_MY_COMMENT
            + (query.hasCursor() ? BEFORE_CURSOR : "")
            // 인덱스·커서 키와 같은 순서여야 한다. 어긋나면 페이지 경계에서 누락이 생긴다.
            + " ORDER BY c.createdAt DESC, c.id DESC";

    TypedQuery<MyComment> typed =
        entityManager
            .createQuery(jpql, MyComment.class)
            .setParameter("authorId", query.authorId())
            // 삭제·블라인드 본문은 작성자 본인에게도 막혀 있어 자리표시자로 남길 이유가 없다 (도메인 7.1).
            .setParameter("status", CommentStatus.ACTIVE);

    if (query.hasCursor()) {
      MyCommentCursor cursor = query.cursor();
      typed
          .setParameter("cursorCreatedAt", cursor.createdAt())
          .setParameter("cursorId", cursor.id());
    }

    // OFFSET 을 쓰지 않는다. 한 건을 더 읽어 다음 페이지 유무를 판정한다.
    return typed.setMaxResults(query.size() + 1).getResultList();
  }
}
