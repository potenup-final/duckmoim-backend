package com.duckmoim.catalog.service;

import com.duckmoim.catalog.domain.Event;
import com.duckmoim.catalog.domain.EventQuery;
import com.duckmoim.catalog.domain.Region;
import com.duckmoim.catalog.exception.EventErrorCode;
import com.duckmoim.catalog.infra.EventRepository;
import com.duckmoim.catalog.infra.RegionRepository;
import com.duckmoim.common.exception.BusinessException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 행사 조회 — 목록(EV-05 · EV-06)과 상세(EV-07). */
@Service
public class EventQueryService {

  private final EventRepository eventRepository;
  private final RegionRepository regionRepository;
  private final Clock clock;
  private final Duration staleAfter;

  public EventQueryService(
      EventRepository eventRepository,
      RegionRepository regionRepository,
      Clock clock,
      @Value("${duckmoim.catalog.stale-after}") Duration staleAfter) {
    this.eventRepository = eventRepository;
    this.regionRepository = regionRepository;
    this.clock = clock;
    this.staleAfter = staleAfter;
  }

  /**
   * 조건에 맞는 행사 한 페이지를 읽는다.
   *
   * <p>읽기 전용 트랜잭션 안에서 결과 객체까지 만들어 내보낸다. {@code open-in-view: false} 라 트랜잭션 밖에서 엔티티를 건드리면 그 자리에서
   * 실패하고, 그것이 엔티티가 presentation 으로 새는 것을 막는 두 번째 방어다.
   *
   * <p>오늘 날짜를 여기서 한 번 정해 저장소로 내려보낸다. 저장소가 스스로 시계를 읽으면 같은 요청 안에서 자정을 넘길 때 조건과 커서가 다른 날을 보게 된다.
   *
   * <p><b>원본에서 사라진 행사도 함께 거른다</b> (D-7). 기준은 「크롤러가 마지막으로 본 시각」이고, 그것이 {@code staleAfter} 보다 오래되면
   * 목록에서 빠진다. 상세 조회는 이 조건을 걸지 않는다 — 이미 발급된 링크가 죽으면 안 되고, 즐겨찾기도 그 주소로 돌아온다 (EV-07 · EV-11).
   */
  @Transactional(readOnly = true)
  public EventSlice findEvents(EventQuery query) {
    LocalDate today = LocalDate.now(clock);
    List<Event> found = eventRepository.findSlice(query, today, clock.instant().minus(staleAfter));

    return EventSlice.of(found, query.size(), districtByRegionId());
  }

  /**
   * 행사 한 건을 외부 식별자로 읽는다 (EV-07).
   *
   * <p><b>끝난 행사도 준다.</b> 목록과 달리 {@code today} 를 보지 않는다 — 이 엔드포인트는 애초에 목록에 없는 행사로 직접 들어오는 경로(만료된 링크,
   * 재검증 사이에 추가된 행사)를 위해 있다 (API 설계 2-3). 여기서 끝난 행사를 거르면 그 경로가 그대로 막힌다.
   *
   * <p>목록과 같은 결과 객체를 돌려준다. 프론트가 상세 화면을 목록 배열로 그리고 있어서 (화면 계약 「왜 목록에 거의 다 실어야 하는가」) 모양을 가르면 같은 화면에
   * 렌더러가 둘이 된다.
   */
  @Transactional(readOnly = true)
  public EventSummary findEvent(String externalId) {
    Event event =
        eventRepository
            .findByExternalId(externalId)
            .orElseThrow(() -> new BusinessException(EventErrorCode.EVENT_NOT_FOUND));

    return EventSummary.from(event, districtByRegionId().get(event.getRegionId()));
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
