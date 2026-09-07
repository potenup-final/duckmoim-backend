package com.duckmoim.companion.exception;

import com.duckmoim.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 모집글 에러 코드 (API-설계.md 「4. 에러 코드」).
 *
 * <p><b>정본에 다섯이 있는데 둘만 넣는다.</b> CompanionPostRepository 가 <i>"PostErrorCode 는 Companion 담당의 파일이고,
 * 그것을 여기서 만들면 양쪽이 같은 파일을 고쳐 충돌한다"</i> 고 적어두었다. 그래도 댓글 작성이 모집글을 읽고 404 · 409 를 내야 해서 코드가 필요하고, 이름을
 * 새로 지어내면 클라이언트 계약이 정본과 갈라진다. 그래서 정본대로 만들되 이 티켓이 던지는 둘만 넣어 충돌 면적을 줄인다.
 *
 * <p>메시지는 API-컨벤션.md 「에러코드 체계」의 예시가 적어둔 문장을 그대로 쓴다.
 */
@Getter
@RequiredArgsConstructor
public enum PostErrorCode implements ErrorCode {
  POST_NOT_FOUND(HttpStatus.NOT_FOUND, "모집글을 찾을 수 없습니다."),
  POST_ALREADY_CLOSED(HttpStatus.CONFLICT, "이미 마감된 모집글입니다.");

  private final HttpStatus status;
  private final String message;

  @Override
  public String getCode() {
    return name();
  }
}
