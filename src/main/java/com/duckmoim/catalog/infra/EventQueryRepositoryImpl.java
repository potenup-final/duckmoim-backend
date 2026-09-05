package com.duckmoim.catalog.infra;

import com.duckmoim.catalog.domain.Event;
import com.duckmoim.catalog.domain.EventCursor;
import com.duckmoim.catalog.domain.EventQuery;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class EventQueryRepositoryImpl implements EventQueryRepository {

  private static final String KEYWORD_WILDCARD = "%";
  private static final char KEYWORD_ESCAPE = '!';

  @PersistenceContext private EntityManager entityManager;

  @Override
  public List<Event> findSlice(EventQuery query, LocalDate today) {
    CriteriaBuilder builder = entityManager.getCriteriaBuilder();
    CriteriaQuery<Event> criteria = builder.createQuery(Event.class);
    Root<Event> event = criteria.from(Event.class);

    criteria
        .select(event)
        .where(toPredicates(builder, event, query, today))
        // 커서 키와 같은 순서여야 한다. 어긋나면 페이지 경계에서 누락이 생긴다 (EV-06).
        .orderBy(builder.asc(event.get("endsOn")), builder.asc(event.get("id")));

    return entityManager
        .createQuery(criteria)
        // OFFSET 을 쓰지 않는다 (EV-06). 한 건을 더 읽어 다음 페이지 유무를 판정한다.
        .setMaxResults(query.size() + 1)
        .getResultList();
  }

  private Predicate[] toPredicates(
      CriteriaBuilder builder, Root<Event> event, EventQuery query, LocalDate today) {
    List<Predicate> predicates = new ArrayList<>();

    // 끝난 행사는 목록에 넣지 않는다. 사용자가 끌 수 있는 필터가 아니라 목록의 성질이다
    // (화면-계약/행사.md 9장 — "지난 정보는 없는 정보보다 나쁘다").
    predicates.add(builder.greaterThanOrEqualTo(event.get("endsOn"), today));

    if (query.kind() != null) {
      predicates.add(builder.equal(event.get("kind"), query.kind()));
    }
    if (query.regionId() != null) {
      predicates.add(builder.equal(event.get("regionId"), query.regionId()));
    }
    addPeriodOverlap(builder, event, query, predicates);
    if (query.keyword() != null) {
      predicates.add(keywordMatches(builder, event, query.keyword()));
    }
    if (query.hasCursor()) {
      predicates.add(afterCursor(builder, event, query.cursor()));
    }

    return predicates.toArray(new Predicate[0]);
  }

  /**
   * 행사일 범위 필터 (EV-05).
   *
   * <p>행사는 하루가 아니라 {@code startsOn ~ endsOn} 기간을 갖는다. 그래서 시작일이 범위 안인지가 아니라 <b>범위와 겹치는지</b>로 판정한다 —
   * 지난주에 시작해 다음 달까지 하는 팝업은 "이번 주" 로 걸러도 나와야 한다.
   */
  private void addPeriodOverlap(
      CriteriaBuilder builder, Root<Event> event, EventQuery query, List<Predicate> predicates) {
    LocalDate from = query.dateFrom();
    LocalDate to = query.dateTo();

    if (from != null) {
      predicates.add(builder.greaterThanOrEqualTo(event.get("endsOn"), from));
    }
    if (to != null) {
      predicates.add(builder.lessThanOrEqualTo(event.get("startsOn"), to));
    }
  }

  /**
   * 키워드 검색 (EV-05).
   *
   * <p>{@code subject} 는 정규화된 대상명이고 {@code title} 은 수집원의 원제다. 둘 다 봐야 "아이브" 로도 "IVE 팝업스토어" 로도 걸린다.
   *
   * <p>{@code LOWER()} 를 씌우지 않는다. 콜레이션이 {@code utf8mb4_0900_ai_ci} 라 비교가 이미 대소문자를 무시하고, 함수를 씌우면 나중에
   * {@code subject} 에 인덱스를 걸어도 쓰이지 않는다.
   */
  private Predicate keywordMatches(CriteriaBuilder builder, Root<Event> event, String keyword) {
    String pattern = KEYWORD_WILDCARD + escapeLike(keyword) + KEYWORD_WILDCARD;

    return builder.or(
        builder.like(event.get("subject"), pattern, KEYWORD_ESCAPE),
        builder.like(event.get("title"), pattern, KEYWORD_ESCAPE));
  }

  /**
   * 사용자가 넣은 {@code %} 와 {@code _} 를 찾을 글자로 되돌린다.
   *
   * <p>그대로 두면 {@code keyword=%} 가 전체를 매칭하고 {@code _} 가 임의의 한 글자가 된다. 주입은 아니지만 EV-05 의 검증 기준이 "필터
   * 조합별 결과 정확" 이다.
   *
   * <p>이스케이프 문자 자신을 <b>가장 먼저</b> 치환해야 한다. 나중에 하면 앞서 붙인 이스케이프까지 다시 이스케이프해서 패턴이 망가진다.
   */
  private String escapeLike(String keyword) {
    return keyword
        .replace(String.valueOf(KEYWORD_ESCAPE), KEYWORD_ESCAPE + String.valueOf(KEYWORD_ESCAPE))
        .replace("%", KEYWORD_ESCAPE + "%")
        .replace("_", KEYWORD_ESCAPE + "_");
  }

  /**
   * 커서 위치 다음부터 읽는다 (EV-06).
   *
   * <p>{@code (endsOn, id) > (?, ?)} 튜플 비교를 Criteria 로 풀어 쓴 것이다. 뒤쪽 {@code AND} 절이 EV-06 의 검증 기준을
   * 지킨다 — 종료일이 같은 행사들 사이를 id 로 갈라서, 경계에 걸린 행사가 두 페이지에 다 나오거나 어느 쪽에도 안 나오는 일이 없게 한다.
   */
  private Predicate afterCursor(CriteriaBuilder builder, Root<Event> event, EventCursor cursor) {
    return builder.or(
        builder.greaterThan(event.get("endsOn"), cursor.endsOn()),
        builder.and(
            builder.equal(event.get("endsOn"), cursor.endsOn()),
            builder.greaterThan(event.get("id"), cursor.id())));
  }
}
