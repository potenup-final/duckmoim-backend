package com.duckmoim.companion.exception;

import com.duckmoim.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 댓글 에러 코드 (API-설계.md 「4. 에러 코드」).
 *
 * <p>정본의 다섯을 다 쓴다. STAR-54 가 던질 자리가 없던 셋을 미뤄 두었고, 수정·삭제(CM-09 · CM-10)가 그 자리를 만들었다.
 *
 * <p><b>403 이 둘로 갈린다.</b> 수정은 작성자만 하고 삭제는 작성자 또는 방장이 한다 (API-설계.md 「2-5. 댓글 (Companion)」). 하나로 합치면
 * 방장이 지울 수 있는데 「작성자만」 이라고 답하게 된다. COMMENT_NOT_AUTHOR_OR_HOST 는 이 티켓에서 정본에 더한 이름이다.
 *
 * <p><b>COMMENT_NOT_ACTIVE 는 STAR-81 이 더한 이름이다.</b> 정본에 없다 — 도메인-모델링.md 「6. 라이프사이클」이 두 전이가 모두
 * ACTIVE 에서만 출발한다고만 정하고 그때 무엇을 돌려줄지는 적지 않았다. 에러 표가 <i>"아래는 요구사항에서 직접 나오는 것만이고, 구현하며 늘어난다"</i> 고 열어
 * 둔 자리다.
 *
 * <p><b>409 이고 404 가 아니다.</b> 종착 전이를 다시 부른 것이라 모집글 마감의 POST_ALREADY_CLOSED 와 같은 상황이고, 대상이 없는 것이
 * 아니다. 수정·삭제가 같은 조건에 COMMENT_NOT_FOUND 404 를 쓰는 것은 그쪽 요청자가 일반 사용자라 자리표시자 너머를 몰라도 되기 때문인데, 블라인드를 부르는
 * 쪽은 관리자이고 CM-17 로 그 본문을 읽을 수 있어 「없다」가 사실이 아니다.
 *
 * <p><b>domain 이 아니라 여기 산다.</b> ErrorCode 가 HttpStatus 를 들고 있어 domain 에 두면 domain 이 Spring 에 의존하지
 * 않는다는 규칙과 정면으로 부딪힌다. CommonErrorCode 가 common/exception 에 있는 것과 같은 배치다.
 */
@Getter
@RequiredArgsConstructor
public enum CommentErrorCode implements ErrorCode {
  COMMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "댓글을 찾을 수 없습니다."),
  COMMENT_DEPTH_EXCEEDED(HttpStatus.BAD_REQUEST, "대댓글에는 답글을 달 수 없습니다."),
  COMMENT_SECRET_NOT_CHANGEABLE(HttpStatus.BAD_REQUEST, "비밀 여부는 바꿀 수 없습니다."),
  COMMENT_NOT_AUTHOR(HttpStatus.FORBIDDEN, "작성자만 수정할 수 있습니다."),
  COMMENT_NOT_AUTHOR_OR_HOST(HttpStatus.FORBIDDEN, "작성자 또는 방장만 삭제할 수 있습니다."),
  COMMENT_NOT_ACTIVE(HttpStatus.CONFLICT, "이미 지워지거나 가려진 댓글입니다.");

  private final HttpStatus status;
  private final String message;

  @Override
  public String getCode() {
    return name();
  }
}
