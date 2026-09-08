package com.duckmoim.companion.infra;

import com.duckmoim.companion.domain.UserPostCursor;
import com.duckmoim.companion.domain.UserPostListQuery;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import java.util.List;

/**
 * <b>작성 최신순으로 소유자의 모집글을 읽는다</b> (API-설계.md 「3. 커서 정의」의 「유저가 쓴 모집글」 행).
 *
 * <p>조인 모양은 {@code CompanionPostQueryRepositoryImpl} 과 같다 — 작성자 블록에 닉네임 · 이미지 · 최근 접속이 실리고, 응답의
 * {@code eventId} 가 외부 식별자라 {@code Event} 를 왼쪽 조인한다. 그 문장을 그대로 재사용하지 않고 여기 다시 적은 이유는 <b>정렬과 커서가 다르기
 * 때문</b>이고, 상수를 공유하면 한쪽 정렬을 고칠 때 다른 쪽이 조용히 따라 바뀐다.
 *
 * <p><b>상태로 거르지 않는다.</b> 마감된 글도 내역에 남아야 한다 — 모집글은 소프트 삭제가 없고(결정 D-3) 상태가 {@code OPEN}·{@code
 * CLOSED} 둘이다. 그래서 {@code V23} 인덱스에도 {@code status} 가 없다.
 *
 * <p>커서를 행 값 비교로 적지 않은 것은 JPQL 에 행 값 생성자가 없어서다. 풀어 쓴 {@code OR} 형태가 {@code V23} 의 인덱스 모양을 판단한 기준이다.
 */
public class UserPostQueryRepositoryImpl implements UserPostQueryRepository {

  private static final String SELECT_USER_POST =
      """
      SELECT new com.duckmoim.companion.infra.AuthoredPost(
                 p, u.nickname, u.profileImageUrl, u.lastSeenAt, e.externalId)
        FROM CompanionPost p
        JOIN User u ON u.id = p.hostId
        LEFT JOIN Event e ON e.id = p.eventId
       WHERE p.hostId = :hostId
      """;

  /** 튜플 비교를 풀어 쓴 것이다. createdAt 이 같을 때 id 가 순서를 정한다 (V23). */
  private static final String BEFORE_CURSOR =
      """
         AND (p.createdAt < :cursorCreatedAt
              OR (p.createdAt = :cursorCreatedAt AND p.id < :cursorId))
      """;

  @PersistenceContext private EntityManager entityManager;

  @Override
  public List<AuthoredPost> findUserPostSlice(UserPostListQuery query) {
    String jpql =
        SELECT_USER_POST
            + (query.hasCursor() ? BEFORE_CURSOR : "")
            // 인덱스·커서 키와 같은 순서여야 한다. 어긋나면 페이지 경계에서 누락이 생긴다 (V23).
            + " ORDER BY p.createdAt DESC, p.id DESC";

    TypedQuery<AuthoredPost> typed =
        entityManager.createQuery(jpql, AuthoredPost.class).setParameter("hostId", query.hostId());

    if (query.hasCursor()) {
      UserPostCursor cursor = query.cursor();
      typed
          .setParameter("cursorCreatedAt", cursor.createdAt())
          .setParameter("cursorId", cursor.id());
    }

    // OFFSET 을 쓰지 않는다 (API 컨벤션). 한 건을 더 읽어 다음 페이지 유무를 판정한다.
    return typed.setMaxResults(query.size() + 1).getResultList();
  }
}
