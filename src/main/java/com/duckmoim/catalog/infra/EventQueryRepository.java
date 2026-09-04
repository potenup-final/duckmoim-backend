package com.duckmoim.catalog.infra;

import com.duckmoim.catalog.domain.Event;
import com.duckmoim.catalog.domain.EventQuery;
import java.util.List;

/**
 * 행사 목록의 동적 조회 (EV-05 · EV-06).
 *
 * <p>필터 넷이 각각 선택이라 파생 쿼리 메서드로는 조합을 감당할 수 없다. 커스텀 프래그먼트로 빼고 {@link EventRepository} 가 함께 상속한다 —
 * service 에는 여전히 저장소 하나만 주입된다 (아키텍처 컨벤션 · 저장소).
 */
public interface EventQueryRepository {

  /**
   * 조건에 맞는 행사를 {@code (startsOn, id)} 오름차순으로 읽는다.
   *
   * <p><b>{@code size + 1} 건을 읽는다.</b> 한 건이 더 나오면 다음 페이지가 있다는 뜻이다. 별도 count 쿼리를 돌리지 않는 이유는 그쪽이 필터
   * 조합마다 전체를 훑기 때문이다 — 목록 응답에 총 건수가 필요하지도 않다 (API 컨벤션은 items · nextCursor · hasNext 셋만 쓴다).
   */
  List<Event> findSlice(EventQuery query);
}
