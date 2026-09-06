package com.duckmoim.catalog.domain;

/**
 * 행사 장소의 종류 (화면-계약.md 1장 「{@code Place}」).
 *
 * <p>{@code CONCERT_HALL} 은 EV-10 이 필수로 올라가면서 계약에 들어왔다 (2026-09-05). 그 전에는 둘뿐이라 계약의 콘서트 예시조차 아레나를
 * {@code POPUP_VENUE} 로 적고 있었다.
 *
 * <p><b>크롤러는 아직 이 값을 안 내보낸다.</b> 그래도 여기 둔다 — 계약이 정본이고 구현이 따라오는 순서이지, 수집기가 보내기 시작한 뒤에 받을 자리를 만드는 것이
 * 아니다. 없는 상태에서 값이 들어오면 {@code EnumType.STRING} 매핑이 조회 시점에 터진다.
 */
public enum PlaceKind {
  CAFE,
  POPUP_VENUE,
  CONCERT_HALL
}
