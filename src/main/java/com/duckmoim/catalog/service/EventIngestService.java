package com.duckmoim.catalog.service;

import com.duckmoim.catalog.domain.Event;
import com.duckmoim.catalog.domain.Region;
import com.duckmoim.catalog.exception.EventErrorCode;
import com.duckmoim.catalog.infra.EventRepository;
import com.duckmoim.catalog.infra.RegionRepository;
import com.duckmoim.common.exception.BusinessException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 크롤러가 보낸 행사를 적재한다 (EV-03 · EV-04).
 *
 * <p>외부 식별자 기준 upsert 다 (I-12). 검증 기준이 「재실행해도 중복 생성 없음」이라, 같은 요청을 두 번 받아도 행이 늘지 않아야 한다.
 */
@Service
public class EventIngestService {

  private final EventRepository eventRepository;
  private final RegionRepository regionRepository;
  private final Clock clock;
  private final Duration staleAfter;

  public EventIngestService(
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
   * 한 배치를 적재하고 건수를 보고한다.
   *
   * <p><b>트랜잭션 하나다.</b> 절반만 들어가고 죽으면 DB 가 어중간한 상태로 남는다. 지금은 적재가 실패해도 직전 커밋의 {@code events.json} 이
   * 배포돼 있어 서비스가 멀쩡하지만, 전환 후에는 그 안전망이 없다 (EV-08).
   *
   * <p><b>기준 시각을 한 번만 읽는다.</b> 건마다 {@code clock.instant()} 을 부르면 같은 배치의 행들이 서로 다른 시각을 갖게 되고, 임계에 걸친
   * 행이 배치 안에서 갈린다.
   */
  @Transactional
  public EventIngestResult ingest(EventIngestCommand command) {
    Instant crawledAt = clock.instant();
    Map<String, Long> regionIds = regionIdsByCode();
    Map<String, Event> existing = existingByExternalId(command);

    List<Event> created = new ArrayList<>();

    for (EventIngestCommand.Item item : command.items()) {
      Long regionId = regionIdOf(regionIds, item.districtCode());
      Event event = existing.get(item.crawl().externalId());

      if (event == null) {
        created.add(Event.crawled(item.crawl(), regionId, crawledAt));
      } else {
        event.syncFrom(item.crawl(), regionId, crawledAt);
      }
    }
    eventRepository.saveAll(created);

    return report(created.size(), command.items().size() - created.size(), crawledAt);
  }

  /**
   * 지역 코드를 id 로 바꿀 표를 미리 읽는다.
   *
   * <p>서울 구역이 열한 개뿐이라 전량을 읽는 것이 건마다 조회하는 것보다 싸다 (화면-계약 「{@code Place}」).
   */
  private Map<String, Long> regionIdsByCode() {
    return regionRepository.findAll().stream()
        .collect(Collectors.toMap(Region::getCode, Region::getId));
  }

  private Map<String, Event> existingByExternalId(EventIngestCommand command) {
    List<String> externalIds =
        command.items().stream().map(item -> item.crawl().externalId()).toList();

    return eventRepository.findByExternalIdIn(externalIds).stream()
        .collect(Collectors.toMap(Event::getExternalId, Function.identity()));
  }

  /**
   * 없는 지역 코드는 400 으로 되돌린다.
   *
   * <p>기본 구역으로 떨어뜨리지 않는다. 지도와 필터가 이 값으로 도는데, 조용히 {@code etc} 로 담으면 화면에서는 정상으로 보이면서 핀이 엉뚱한 곳에 선다 —
   * 크롤러가 오타를 낸 것을 알 방법이 없어진다.
   */
  private Long regionIdOf(Map<String, Long> regionIds, String districtCode) {
    Long regionId = regionIds.get(districtCode);

    if (regionId == null) {
      throw new BusinessException(EventErrorCode.EVENT_REGION_UNKNOWN);
    }
    return regionId;
  }

  /**
   * 건수를 센다 (EV-04).
   *
   * <p>{@code today} 는 KST 기준이다. 목록이 「끝난 행사」를 날짜로 거르므로 어느 시간대의 오늘인지가 결과를 바꾼다 (도메인 4장).
   */
  private EventIngestResult report(int created, int updated, Instant crawledAt) {
    LocalDate today = LocalDate.ofInstant(crawledAt, clock.getZone());
    long hidden = eventRepository.countStale(today, crawledAt.minus(staleAfter));

    return new EventIngestResult(eventRepository.count(), created, updated, hidden);
  }
}
