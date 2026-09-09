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
 * <p>필드 이름과 모양은 계약이 정한다. <b>정본은 화면-계약.md 1장</b>이고 프론트 {@code types.ts} 와 이 코드가 둘 다 거기를 보고 맞춘다 (「어느
 * 것이 정본인가」). 쓰는 쪽이 크롤러 · 프론트 · 백엔드 셋이라 어느 코드도 정본이 될 수 없어서 계약이 문서에 있다. 특히 {@code place} 는 중첩 객체다 —
 * 저장은 평평하지만 계약은 중첩이라 여기서 다시 묶는다.
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
    String perks,
    String conditions,
    String imageUrl,
    String sourceUrl,
    String reservationUrl,
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
        event.perks(),
        event.conditions(),
        event.imageUrl(),
        event.sourceUrl(),
        event.reservationUrl(),
        event.regionId(),
        new PlaceResponse(
            event.placeName(),
            event.placeAddress(),
            event.placeLat(),
            event.placeLng(),
            event.district(),
            event.placeKind()));
  }

  /**
   * 행사 장소 (화면-계약.md 1장 「{@code Place}」).
   *
   * <p>{@code district} 만 소문자다. 열거형이 아니라 {@code region.code} 라 시드 값({@code hongdae} · {@code
   * seongsu})과 글자가 같아야 한다.
   */
  public record PlaceResponse(
      String name,
      String address,
      BigDecimal lat,
      BigDecimal lng,
      String district,
      PlaceKind kind) {}
}
