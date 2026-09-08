package com.duckmoim.identity.presentation.dto;

/**
 * 닉네임 사전 조회 응답 (AU-06).
 *
 * <p><b>{@code isAvailable} 이 아니라 {@code available} 이다</b> — boolean 에 {@code is} 접두어를 붙이지 않는다
 * (API-컨벤션.md 「필드 표기 규칙」).
 *
 * <p>모양이 위키에 없어서 정했다. 한 값이라 원시 boolean 을 그대로 내릴 수도 있었으나, 그러면 나중에 「왜 쓸 수 없는지」를 함께 내려야 할 때 응답 형태가 바뀐다
 * — 객체로 감싸 두면 필드를 더하는 것으로 끝난다.
 */
public record NicknameAvailabilityResponse(boolean available) {

  public static NicknameAvailabilityResponse of(boolean available) {
    return new NicknameAvailabilityResponse(available);
  }
}
