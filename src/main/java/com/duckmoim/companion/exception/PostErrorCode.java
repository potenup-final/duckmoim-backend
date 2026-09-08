package com.duckmoim.companion.exception;

import com.duckmoim.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 모집글 에러 코드 (API-설계.md 「4. 에러 코드」).
 *
 * <p>댓글 티켓이 자기가 던지는 둘만 먼저 넣어 두었다 — 정본에 다섯이 있는데 <i>"PostErrorCode 는 Companion 담당의 파일이고, 그것을 여기서 만들면
 * 양쪽이 같은 파일을 고쳐 충돌한다"</i> 는 이유였다. 모집글 작성(STAR-66)이 셋을 채우고 수정·마감(PO-06 · PO-07)이 마지막 하나를 채웠다. <b>이제
 * 정본의 다섯이 다 있다.</b>
 *
 * <p>메시지는 API-컨벤션.md 「에러코드 체계」의 예시가 적어둔 문장을 그대로 쓴다.
 */
@Getter
@RequiredArgsConstructor
public enum PostErrorCode implements ErrorCode {
  POST_NOT_FOUND(HttpStatus.NOT_FOUND, "모집글을 찾을 수 없습니다."),
  POST_ALREADY_CLOSED(HttpStatus.CONFLICT, "이미 마감된 모집글입니다."),
  POST_NOT_HOST(HttpStatus.FORBIDDEN, "방장만 수정하거나 마감할 수 있습니다."),
  POST_CAPACITY_OUT_OF_RANGE(HttpStatus.BAD_REQUEST, "정원은 2명 이상 6명 이하여야 합니다."),
  POST_MEET_AT_AFTER_EVENT_END(HttpStatus.BAD_REQUEST, "만남시각이 행사 종료일보다 늦습니다.");

  private final HttpStatus status;
  private final String message;

  @Override
  public String getCode() {
    return name();
  }
}
