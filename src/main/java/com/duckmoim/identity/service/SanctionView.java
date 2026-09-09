package com.duckmoim.identity.service;

import java.time.OffsetDateTime;

/**
 * {@code GET /users/me} 응답의 {@code sanction} 에 실리는 값 (AU-12 · 화면-계약.md 「제재 상태 — AU-12」).
 *
 * <p><b>{@code kind} 가 열거형이 아니라 문자열이다.</b> 값 집합({@code NONE}·{@code WARNED}·{@code AGE_HOLD}·{@code
 * SUSPENDED}·{@code BANNED})은 Safety 의 것이고 아직 {@code Sanction} 이 없다. 여기서 열거형을 만들면 Safety 가 자기 것을
 * 만드는 순간 <b>같은 값 집합이 두 곳에 생긴다.</b> 실구현이 들어올 때 그쪽 열거값의 이름을 그대로 넣는다.
 *
 * <p>{@code until} 은 {@code SUSPENDED} 일 때만 값이 있고, {@code reason} 은 본인에게 그대로 보여준다 (화면 계약 3장).
 *
 * <p>시각은 저장이 UTC 이고 응답이 KST 오프셋을 포함한다 (API-컨벤션.md 「필드 표기 규칙」).
 */
public record SanctionView(
    String kind, String reason, OffsetDateTime until, OffsetDateTime issuedAt) {

  private static final String NONE = "NONE";

  /** 제재가 없는 상태. 화면 계약 표의 첫 줄이다 — 화면 없음 · 쓰기 가능. */
  public static SanctionView none() {
    return new SanctionView(NONE, null, null, null);
  }
}
