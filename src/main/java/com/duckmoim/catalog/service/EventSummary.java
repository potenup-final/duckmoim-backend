package com.duckmoim.catalog.service;

import com.duckmoim.catalog.domain.Event;
import com.duckmoim.catalog.domain.EventKind;
import com.duckmoim.catalog.domain.PlaceKind;
import com.duckmoim.catalog.domain.SubjectType;
import com.duckmoim.catalog.domain.Trust;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 목록에 실리는 행사 한 건 (EV-05).
 *
 * <p>엔티티를 presentation 까지 올리지 않기 위한 결과 객체다 (API 컨벤션 · 리뷰 체크리스트). 트랜잭션 안에서 값을 다 뽑아두므로 {@code
 * open-in-view: false} 아래에서 지연 로딩이 새지 않는다.
 *
 * <p><b>목록에 넣지 않는 것</b> — {@code goods} · {@code perks} · {@code conditions} 는 상세(EV-07) 소관이다. 특히
 * {@code goods} 는 지연 컬렉션이라 목록에서 건드리면 N+1 이 된다.
 */
public record EventSummary(
    Long id,
    String externalId,
    EventKind kind,
    SubjectType subjectType,
    Trust trust,
    String subject,
    String title,
    LocalDate startsOn,
    LocalDate endsOn,
    String openHours,
    LocalTime startsAt,
    String imageUrl,
    String sourceUrl,
    Long regionId,
    String placeName,
    String placeAddress,
    BigDecimal placeLat,
    BigDecimal placeLng,
    PlaceKind placeKind) {

  static EventSummary from(Event event) {
    return new EventSummary(
        event.getId(),
        event.getExternalId(),
        event.getKind(),
        event.getSubjectType(),
        event.getTrust(),
        event.getSubject(),
        event.getTitle(),
        event.getStartsOn(),
        event.getEndsOn(),
        event.getOpenHours(),
        event.getStartsAt(),
        event.getImageUrl(),
        event.getSourceUrl(),
        event.getRegionId(),
        event.getPlaceName(),
        event.getPlaceAddress(),
        event.getPlaceLat(),
        event.getPlaceLng(),
        event.getPlaceKind());
  }
}
