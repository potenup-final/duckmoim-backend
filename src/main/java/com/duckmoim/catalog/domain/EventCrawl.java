package com.duckmoim.catalog.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 크롤러가 한 행사에서 긁어 온 값 (EV-03).
 *
 * <p>불변이고 식별자를 갖지 않는다. {@link Event} 가 이것을 받아 자신을 만들거나 갱신한다 — 필드가 스물이라 팩터리 인자로 늘어놓으면 호출부에서 순서가 틀려도
 * 컴파일이 통과한다.
 *
 * <p><b>{@code source} 를 여기서 받는다.</b> {@code externalId} 접두어에서 유도하지 않는다 (API-설계 D-9). 그 값은 사용자에게
 * 보이는 주소이고(도메인 4장) 규칙을 바꾸면 색인된 주소가 죽는다 — 거기에 출처 판정까지 얹으면 한 값이 셋을 진다.
 *
 * <p><b>{@code regionId} 는 없다.</b> 크롤러는 지역 코드 문자열을 보내고 그것을 id 로 바꾸는 것은 {@code Region} 조회다. 애그리게이트
 * 밖이라 도메인 안에서 풀 수 없다 (도메인 3.2).
 *
 * <p><b>{@code goods} 도 없다.</b> 어느 수집원도 굿즈를 주지 않아 전량 빈 배열이다 (행사-수집원 「남은 한계」). 실제로 채우는 시점에 더한다.
 */
public record EventCrawl(
    String externalId,
    EventSource source,
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
    String sourceUrl,
    String listingUrl,
    String reservationUrl,
    String imageUrl,
    String placeName,
    String placeAddress,
    BigDecimal placeLat,
    BigDecimal placeLng,
    PlaceKind placeKind) {}
