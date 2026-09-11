package com.duckmoim.companion.infra;

import com.duckmoim.companion.domain.PostCursor;
import com.duckmoim.companion.domain.PostListQuery;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JPQL 로 쓴 이유 — {@code CompanionPost} 에 {@code User} 로도 {@code Event} 로도 가는 연관이 없다. 애그리게이트끼리 ID 로만
 * 참조하기 때문이다 (도메인-모델링.md 「3.2 애그리게이트 간 참조 규칙」). Criteria API 는 매핑된 연관이 있어야 조인을 걸 수 있어서, 연관 없는 엔티티
 * 조인({@code JOIN User u ON ...})을 쓰려면 JPQL 이 맞다. {@code CommentQueryRepositoryImpl} 이 같은 이유로 JPQL
 * 이고, {@code EventQueryRepositoryImpl} 이 Criteria 인 것은 그쪽이 선택 필터 넷을 조합하기 때문이다.
 */
public class CompanionPostQueryRepositoryImpl implements CompanionPostQueryRepository {

  /**
   * 행사는 {@code LEFT JOIN} 이다. 행사를 고르지 않은 모집글이 있고 (PO-02), 내부 조인이면 그 글들이 목록에서 통째로 사라진다.
   *
   * <p>방장은 내부 조인이다. 모집글에 방장이 없는 경우가 없고, 탈퇴는 소프트 삭제라 행이 남는다 (도메인-모델링.md 「1. 유비쿼터스 언어」).
   *
   * <p><b>탈퇴한 방장의 글을 여기서 거르지 않는다.</b> 요구사항이 「작성 댓글은 자리표시자 유지」 이고 결정 D-3 이 모집글 삭제를 두지 않았으므로, 전체 목록에서
   * 글이 사라지는 것은 반대 방향이다 (AU-11). 대신 {@code u.status} 를 함께 읽어 <b>작성자 표시만</b> 익명화한다.
   */
  private static final String SELECT_AUTHORED =
      """
      SELECT new com.duckmoim.companion.infra.AuthoredPost(
                 p, u.nickname, u.profileImageUrl, u.lastSeenAt, e.externalId, u.status)
        FROM CompanionPost p
        JOIN User u ON u.id = p.hostId
        LEFT JOIN Event e ON e.id = p.eventId
      """;

  /** 튜플 비교를 풀어 쓴 것이다. meetAt 이 같을 때 id 가 순서를 정한다 (PO-08). */
  private static final String AFTER_CURSOR =
      "(p.meetAt > :cursorMeetAt OR (p.meetAt = :cursorMeetAt AND p.id > :cursorId))";

  @PersistenceContext private EntityManager entityManager;

  @Override
  public List<AuthoredPost> findSlice(PostListQuery query) {
    // 걸지 않는 조건은 파라미터로 무력화하지 않고 문장에서 뺀다. 항상 참인 조건을 남겨두면
    // status 를 생략한 「전체」 조회가 (meet_at, id) 인덱스를 못 쓴다 (V22).
    List<String> conditions = new ArrayList<>();
    if (query.hasStatus()) {
      conditions.add("p.status = :status");
    }
    if (query.hasCursor()) {
      conditions.add(AFTER_CURSOR);
    }

    String jpql =
        SELECT_AUTHORED
            + (conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions))
            // 인덱스·커서 키와 같은 순서여야 한다. 어긋나면 페이지 경계에서 누락이 생긴다 (V22).
            + " ORDER BY p.meetAt ASC, p.id ASC";

    TypedQuery<AuthoredPost> typed = entityManager.createQuery(jpql, AuthoredPost.class);

    if (query.hasStatus()) {
      typed.setParameter("status", query.status());
    }
    if (query.hasCursor()) {
      PostCursor cursor = query.cursor();
      typed.setParameter("cursorMeetAt", cursor.meetAt()).setParameter("cursorId", cursor.id());
    }

    // OFFSET 을 쓰지 않는다 (API 컨벤션). 한 건을 더 읽어 다음 페이지 유무를 판정한다.
    return typed.setMaxResults(query.size() + 1).getResultList();
  }

  @Override
  public Optional<AuthoredPost> findAuthored(Long postId) {
    return entityManager
        .createQuery(SELECT_AUTHORED + " WHERE p.id = :postId", AuthoredPost.class)
        .setParameter("postId", postId)
        .getResultList()
        .stream()
        .findFirst();
  }
}
