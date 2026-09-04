package com.duckmoim.catalog.service;

import com.duckmoim.catalog.domain.Event;
import com.duckmoim.catalog.domain.EventQuery;
import com.duckmoim.catalog.infra.EventRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 행사 목록 조회 (EV-05 · EV-06). */
@Service
@RequiredArgsConstructor
public class EventQueryService {

  private final EventRepository eventRepository;

  /**
   * 조건에 맞는 행사 한 페이지를 읽는다.
   *
   * <p>읽기 전용 트랜잭션 안에서 결과 객체까지 만들어 내보낸다. {@code open-in-view: false} 라 트랜잭션 밖에서 엔티티를 건드리면 그 자리에서
   * 실패하고, 그것이 엔티티가 presentation 으로 새는 것을 막는 두 번째 방어다.
   */
  @Transactional(readOnly = true)
  public EventSlice findEvents(EventQuery query) {
    List<Event> found = eventRepository.findSlice(query);

    return EventSlice.of(found, query.size());
  }
}
