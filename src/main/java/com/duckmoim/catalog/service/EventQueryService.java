package com.duckmoim.catalog.service;

import com.duckmoim.catalog.domain.Event;
import com.duckmoim.catalog.domain.EventQuery;
import com.duckmoim.catalog.domain.Region;
import com.duckmoim.catalog.infra.EventRepository;
import com.duckmoim.catalog.infra.RegionRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 행사 목록 조회 (EV-05 · EV-06). */
@Service
@RequiredArgsConstructor
public class EventQueryService {

  private final EventRepository eventRepository;
  private final RegionRepository regionRepository;
  private final Clock clock;

  /**
   * 조건에 맞는 행사 한 페이지를 읽는다.
   *
   * <p>읽기 전용 트랜잭션 안에서 결과 객체까지 만들어 내보낸다. {@code open-in-view: false} 라 트랜잭션 밖에서 엔티티를 건드리면 그 자리에서
   * 실패하고, 그것이 엔티티가 presentation 으로 새는 것을 막는 두 번째 방어다.
   *
   * <p>오늘 날짜를 여기서 한 번 정해 저장소로 내려보낸다. 저장소가 스스로 시계를 읽으면 같은 요청 안에서 자정을 넘길 때 조건과 커서가 다른 날을 보게 된다.
   */
  @Transactional(readOnly = true)
  public EventSlice findEvents(EventQuery query) {
    LocalDate today = LocalDate.now(clock);
    List<Event> found = eventRepository.findSlice(query, today);

    return EventSlice.of(found, query.size(), districtByRegionId());
  }

  /**
   * 지역 번호를 화면이 쓰는 코드 문자열로 바꿀 표를 읽는다.
   *
   * <p>{@code Event} 가 {@code Region} 을 객체로 참조하지 않아서 (도메인 3.2) 조인 대신 표를 통째로 읽어 맞춘다. 11행이라 목록 조회에 쿼리
   * 하나가 더 붙는 정도이고, 지역이 늘어날 성질의 표도 아니다.
   */
  private Map<Long, String> districtByRegionId() {
    return regionRepository.findAll().stream()
        .collect(Collectors.toMap(Region::getId, Region::getCode));
  }
}
