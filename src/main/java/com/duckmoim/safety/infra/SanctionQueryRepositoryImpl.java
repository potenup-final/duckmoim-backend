package com.duckmoim.safety.infra;

import com.duckmoim.safety.domain.SanctionCursor;
import com.duckmoim.safety.domain.SanctionListQuery;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import java.util.List;

/**
 * JPQL 로 쓴 이유는 {@code ReportQueryRepositoryImpl} 과 같다 — {@code Sanction} 에 회원으로 가는 연관이 없다. 애그리게이트끼리
 * ID 로만 참조하기 때문이고 (도메인-모델링.md 「3.2 애그리게이트 간 참조 규칙」), 그래서 FK 도 없다 (V38).
 */
public class SanctionQueryRepositoryImpl implements SanctionQueryRepository {

  /**
   * 제재받은 회원의 이름을 함께 읽는다.
   *
   * <p><b>{@code JOIN} 이다.</b> 제재는 회원에게만 걸리므로 짝이 없으면 데이터가 깨진 것이다 — 신고 대상이 {@code LEFT JOIN} 인 것과
   * 갈리는 지점이고, 그쪽은 대상이 사라져도 신고가 목록에 남아야 했다 (STAR-60).
   */
  private static final String SELECT_SANCTIONED_USER =
      """
      SELECT new com.duckmoim.safety.infra.SanctionedUser(s, u.nickname)
        FROM Sanction s
        JOIN User u ON u.id = s.userId
      """;

  /**
   * 지금 유효한 제재 (검증 기준 ① · ②).
   *
   * <p>푼 것이 빠지고 만료가 지난 것이 빠진다. 만료가 비어 있는 것은 스스로 풀리지 않아 늘 남는다 ({@code AGE_HOLD} · {@code BANNED}).
   *
   * <p><b>「지금」이 조건에 들어가도 되는 자리다.</b> 회원 하나를 읽는 경로는 {@code idx_sanction_active} 를 타야 해서 넣지 않았지만, 이
   * 목록은 좁힐 {@code userId} 가 없어 어차피 {@code released_at} 선두로 훑는다.
   */
  private static final String WHERE_ACTIVE =
      """
       WHERE s.releasedAt IS NULL
         AND (s.expiresAt IS NULL OR s.expiresAt > :now)
      """;

  private static final String AND_KIND = " AND s.kind = :kind";

  /**
   * 만료가 있는 제재 구간을 이어 읽는다.
   *
   * <p>튜플 비교를 풀어 쓴 것이다. 만료 임박순이라 커서 <b>다음</b>으로 이어 읽는다 — 신고 목록과 부등호가 반대다.
   *
   * <p>마지막 줄이 만료 없는 구간을 함께 집는다. 그 구간은 정렬상 전부 뒤에 있어 커서보다 항상 뒤다.
   */
  private static final String AFTER_CURSOR =
      """
         AND (s.expiresAt IS NULL
              OR s.expiresAt > :cursorExpiresAt
              OR (s.expiresAt = :cursorExpiresAt AND s.id > :cursorId))
      """;

  /**
   * 만료 없는 제재 구간을 이어 읽는다.
   *
   * <p>커서가 이 구간을 가리키면 남은 것도 이 구간뿐이라 {@code id} 만 본다. 만료 있는 제재를 함께 집으면 <b>이미 지나온 앞 구간이 다시 나온다.</b>
   */
  private static final String AFTER_CURSOR_WITHOUT_EXPIRY =
      " AND s.expiresAt IS NULL AND s.id > :cursorId";

  /**
   * 만료 임박순. <b>만료가 없는 제재가 뒤로 간다.</b>
   *
   * <p>그냥 {@code s.expiresAt} 으로 정렬하면 MySQL 이 {@code NULL} 을 앞에 놓아 「끝이 없는 것이 가장 임박하다」가 된다 — 목록 첫
   * 화면이 영구 정지로 찬다.
   *
   * <p>{@code id} 를 뒤에 붙인 이유 — 같은 날 같은 기간으로 정지된 둘이 페이지 경계에 걸리면 정렬이 불안정해져 누락·중복이 난다. 커서 키가 이 순서와 같다.
   */
  private static final String ORDER_BY =
      " ORDER BY CASE WHEN s.expiresAt IS NULL THEN 1 ELSE 0 END, s.expiresAt, s.id";

  @PersistenceContext private EntityManager entityManager;

  @Override
  public List<SanctionedUser> findSlice(SanctionListQuery query) {
    String jpql = SELECT_SANCTIONED_USER + WHERE_ACTIVE + kindClause(query) + cursorClause(query);

    TypedQuery<SanctionedUser> typed =
        entityManager
            .createQuery(jpql + ORDER_BY, SanctionedUser.class)
            .setParameter("now", query.now());

    if (query.hasKind()) {
      typed.setParameter("kind", query.kind());
    }
    if (query.hasCursor()) {
      SanctionCursor cursor = query.cursor();
      typed.setParameter("cursorId", cursor.id());

      if (cursor.hasExpiry()) {
        typed.setParameter("cursorExpiresAt", cursor.expiresAt());
      }
    }

    // OFFSET 을 쓰지 않는다. 한 건을 더 읽어 다음 페이지 유무를 판정한다.
    return typed.setMaxResults(query.size() + 1).getResultList();
  }

  private static String kindClause(SanctionListQuery query) {
    return query.hasKind() ? AND_KIND : "";
  }

  /** 커서가 어느 구간을 가리키느냐로 이어 읽는 조건이 갈린다. */
  private static String cursorClause(SanctionListQuery query) {
    if (!query.hasCursor()) {
      return "";
    }

    return query.cursor().hasExpiry() ? AFTER_CURSOR : AFTER_CURSOR_WITHOUT_EXPIRY;
  }
}
