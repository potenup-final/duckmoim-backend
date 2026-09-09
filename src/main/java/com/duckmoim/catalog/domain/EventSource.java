package com.duckmoim.catalog.domain;

/**
 * 행사를 어디서 수집했는가.
 *
 * <p>{@code KOPIS} 는 아직 적재 경로가 없다 (EV-03 미착수). 그래도 먼저 열어 둔다 — 없는 상태로 {@code kopis_} 행이 들어오면 upsert
 * 는 성공하고 {@code EnumType.STRING} 매핑이 조회 시점에 터진다. {@link PlaceKind#CONCERT_HALL} 을 넣을 때와 같은 판단이다.
 */
public enum EventSource {
  POPGA,
  OFFMATE,
  KOPIS
}
