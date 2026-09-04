package com.duckmoim.catalog.infra;

import com.duckmoim.catalog.domain.Event;
import com.duckmoim.catalog.domain.EventQuery;
import java.time.LocalDate;
import java.util.List;

/**
 * 행사 목록의 동적 조회 (EV-05 · EV-06).
 *
 * <p>필터 넷이 각각 선택이라 파생 쿼리 메서드로는 조합을 감당할 수 없다. 커스텀 프래그먼트로 빼고 {@link EventRepository} 가 함께 상속한다 —
 * service 에는 여전히 저장소 하나만 주입된다 (아키텍처 컨벤션 · 저장소).
 */
public interface EventQueryRepository {

  /**
   * {@code (endsOn, id)} 오름차순으로 {@code size + 1} 건까지 읽는다.
   *
   * <p>한 건을 더 읽는 것은 다음 페이지 유무를 알기 위해서다. 별도 count 쿼리를 돌리지 않는 이유는 그쪽이 필터 조합마다 전체를 훑기 때문이다 — 목록 응답에 총
   * 건수가 필요하지도 않다 (API 컨벤션은 items · nextCursor · hasNext 셋만 쓴다).
   *
   * <p>{@code today} 를 인자로 받는 이유는 {@code LocalDate.now()} 를 여기서 부르면 테스트가 실행 날짜에 끌려가기 때문이다. 판정 기준은
   * KST 다 (도메인 4장).
   */
  List<Event> findSlice(EventQuery query, LocalDate today);
}
