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
 * <p><b>상세에만 쓰이는 값도 목록에 싣는다.</b> 프론트에 상세 조회 호출이 따로 없어서 상세 207장을 목록 배열로 정적 생성한다 (화면-계약/행사.md 5장).
 * {@code perks} · {@code conditions} · {@code reservationUrl} 이 그렇고, 셋 다 {@code event} 컬럼이라 이미 읽어온
 * 행에서 값을 싣기만 하면 된다.
 *
 * <p>{@code goods} 만 뺀다. 지연 컬렉션이라 목록에서 건드리면 N+1 이고, 수집한 207건이 전부 빈 배열이라 화면에 나오는 것이 없다 (화면-계약/행사.md
 * 8장).
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
    String perks,
    String conditions,
    String imageUrl,
    String sourceUrl,
    String reservationUrl,
    Long regionId,
    String district,
    String placeName,
    String placeAddress,
    BigDecimal placeLat,
    BigDecimal placeLng,
    PlaceKind placeKind) {

  static EventSummary from(Event event, String district) {
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
        event.getPerks(),
        event.getConditions(),
        event.getImageUrl(),
        event.getSourceUrl(),
        event.getReservationUrl(),
        event.getRegionId(),
        district,
        event.getPlaceName(),
        event.getPlaceAddress(),
        event.getPlaceLat(),
        event.getPlaceLng(),
        event.getPlaceKind());
  }
}
