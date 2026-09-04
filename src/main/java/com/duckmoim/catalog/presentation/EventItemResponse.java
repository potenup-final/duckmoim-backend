package com.duckmoim.catalog.presentation;

import com.duckmoim.catalog.domain.EventKind;
import com.duckmoim.catalog.domain.PlaceKind;
import com.duckmoim.catalog.domain.SubjectType;
import com.duckmoim.catalog.domain.Trust;
import com.duckmoim.catalog.service.EventSummary;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 목록에 실리는 행사 한 건 (EV-05).
 *
 * <p>필드 이름과 모양을 프론트 {@code EventItem} 에 맞춘다 (도메인 모델링 4.1). 특히 {@code place} 는 중첩 객체다 — 저장은 평평하게 하지만
 * 계약은 중첩이라 여기서 다시 묶는다.
 *
 * <p>null 가능 필드를 생략하지 않고 null 로 내보낸다 (API 컨벤션). 키가 있다 없다 하면 클라이언트가 분기를 두 번 써야 한다.
 */
public record EventItemResponse(
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
    @JsonFormat(pattern = "HH:mm") LocalTime startsAt,
    String imageUrl,
    String sourceUrl,
    Long regionId,
    PlaceResponse place) {

  static EventItemResponse from(EventSummary event) {
    return new EventItemResponse(
        event.id(),
        event.externalId(),
        event.kind(),
        event.subjectType(),
        event.trust(),
        event.subject(),
        event.title(),
        event.startsOn(),
        event.endsOn(),
        event.openHours(),
        event.startsAt(),
        event.imageUrl(),
        event.sourceUrl(),
        event.regionId(),
        new PlaceResponse(
            event.placeName(),
            event.placeAddress(),
            event.placeLat(),
            event.placeLng(),
            event.placeKind()));
  }

  /** 행사 장소 (도메인 모델링 1장 {@code Place}). */
  public record PlaceResponse(
      String name, String address, BigDecimal lat, BigDecimal lng, PlaceKind kind) {}
}
