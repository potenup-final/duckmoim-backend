package com.duckmoim.companion.exception;

import com.duckmoim.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 댓글 에러 코드 (API-설계.md 「4. 에러 코드」).
 *
 * <p>정본에 넷이 있는데 둘만 넣는다. COMMENT_SECRET_NOT_CHANGEABLE 와 COMMENT_NOT_AUTHOR 는 수정(CM-09) 소관이고, 던지는
 * 자리가 없는 코드를 미리 만들면 어디서 쓰이는지 모르는 채로 남는다.
 *
 * <p><b>domain 이 아니라 여기 산다.</b> ErrorCode 가 HttpStatus 를 들고 있어 domain 에 두면 domain 이 Spring 에 의존하지
 * 않는다는 규칙과 정면으로 부딪힌다. CommonErrorCode 가 common/exception 에 있는 것과 같은 배치다.
 */
@Getter
@RequiredArgsConstructor
public enum CommentErrorCode implements ErrorCode {
  COMMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "댓글을 찾을 수 없습니다."),
  COMMENT_DEPTH_EXCEEDED(HttpStatus.BAD_REQUEST, "대댓글에는 답글을 달 수 없습니다.");

  private final HttpStatus status;
  private final String message;

  @Override
  public String getCode() {
    return name();
  }
}
