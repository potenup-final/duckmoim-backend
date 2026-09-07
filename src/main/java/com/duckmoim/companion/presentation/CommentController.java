package com.duckmoim.companion.presentation;

import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.common.exception.CommonErrorCode;
import com.duckmoim.companion.service.CommentCommandService;
import com.duckmoim.companion.service.WrittenComment;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "댓글", description = "댓글 작성")
@RestController
@RequestMapping("/api/v1/posts/{postId}/comments")
@RequiredArgsConstructor
public class CommentController {

  /**
   * 요청자 ID 를 담는 <b>임시</b> 헤더.
   *
   * <p>이 엔드포인트는 SIGNUP 등급이다 (API-설계.md 「2-5. 댓글 (Companion)」). 그런데 인증이 아직 한 줄도 없다 — Authorization
   * 파싱도, I-02 · I-14 를 판정할 인터셉터도, Sanction 도 없다. 요청자 없이는 댓글의 작성자를 정할 수 없어 여기서 멈추는 대신 헤더로 받는다.
   *
   * <p><b>요청자 해석이 이 메서드 한 곳에만 있다.</b> AU 티켓이 오면 이 파라미터가 실제 인증 결과로 바뀌고 service · domain 은 그대로다.
   * 인터셉터나 ArgumentResolver 를 미리 세우지 않는 것은 그것이 AU 담당이 만들 파일과 같은 자리이기 때문이다.
   */
  static final String REQUESTER_HEADER = "X-User-Id";

  private final CommentCommandService commentCommandService;

  /**
   * 모집글에 댓글을 쓴다 (CM-01 · CM-02 · CM-03).
   *
   * <p>생성 성공도 200 이다 (API-컨벤션.md 「Status Code 규칙」). 201 을 쓰지 않는다.
   */
  @Operation(
      summary = "댓글 작성",
      description = "parentId 를 주면 대댓글이고 루트 댓글에만 붙는다. secret 은 작성할 때만 정한다.")
  @PostMapping
  public CommentResponse writeComment(
      @PathVariable Long postId,
      @RequestHeader(name = REQUESTER_HEADER, required = false) String requester,
      @Valid @RequestBody CommentWriteRequest request) {

    WrittenComment written =
        commentCommandService.write(request.toCommand(postId, requesterId(requester)));

    return CommentResponse.from(written);
  }

  /**
   * 임시 헤더를 요청자 ID 로 읽는다.
   *
   * <p><b>없거나 숫자가 아니면 INVALID_INPUT 400 이다.</b> 제대로 된 계약은 401 · 403 이지만 (API-설계.md 「1. 권한 등급」) 그
   * 판정과 에러 코드는 인증 인터셉터의 것이고 AuthErrorCode 도 아직 없다. 여기서 401 을 흉내내면 인증이 붙는 날 지워야 하는 계약이 하나 더 생긴다.
   *
   * <p>파라미터를 Long 이 아니라 String 으로 받는 이유가 이것이다. Long 으로 받으면 숫자가 아닌 헤더가 타입 변환 예외가 되고, 전역 핸들러가 그것을 500
   * 으로 떨어뜨린다.
   */
  private Long requesterId(String requester) {
    if (requester == null) {
      throw new BusinessException(CommonErrorCode.INVALID_INPUT);
    }

    try {
      return Long.valueOf(requester);
    } catch (NumberFormatException e) {
      throw new BusinessException(CommonErrorCode.INVALID_INPUT);
    }
  }
}
