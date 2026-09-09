package com.duckmoim.auth.exception;

import com.duckmoim.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum AuthErrorCode implements ErrorCode {
  AUTH_REFRESH_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "다시 로그인해 주세요."),
  AUTH_ACCESS_TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "로그인이 만료되었습니다. 토큰을 재발급해 주세요."),
  AUTH_ACCESS_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."),
  AUTH_FORBIDDEN(HttpStatus.FORBIDDEN, "이 요청을 수행할 권한이 없습니다."),

  /**
   * 카카오가 인가코드를 거절했다 (AU-01).
   *
   * <p><b>401 이다.</b> 인가코드는 자격증명이고, 무효 · 만료 · 이미 사용됨(카카오 KOE320)이 모두 「인증 실패」다. 400 으로 두면 클라이언트가
   * 「입력을 고쳐 다시 보내라」로 읽는데, 인가코드는 한 번 쓰면 끝이라 고칠 것이 없다 — 할 일은 카카오 인가를 다시 받는 것이다.
   */
  AUTH_KAKAO_CODE_INVALID(HttpStatus.UNAUTHORIZED, "카카오 로그인에 실패했습니다. 다시 시도해 주세요."),

  /**
   * 카카오가 응답하지 않거나 5xx 를 줬다 (AU-01).
   *
   * <p><b>상태는 500 이다.</b> API 컨벤션 Status Code 표에 502 · 503 이 없다. 다만 코드와 문구를 {@code INTERNAL_ERROR}
   * 와 따로 둔다 — 우리 버그면 사용자가 할 수 있는 일이 없지만 이쪽은 잠시 뒤 다시 누르면 된다.
   */
  AUTH_KAKAO_UNAVAILABLE(HttpStatus.INTERNAL_SERVER_ERROR, "카카오 로그인이 지연되고 있습니다. 잠시 후 다시 시도해 주세요.");

  private final HttpStatus status;
  private final String message;

  @Override
  public String getCode() {
    return name();
  }
}
