package com.duckmoim.companion.presentation;

import com.duckmoim.companion.domain.Comment;
import com.duckmoim.companion.domain.CommentReadContext;
import com.duckmoim.companion.domain.CommentReadTarget;
import com.duckmoim.companion.domain.CommentVisibilityPolicy;
import lombok.RequiredArgsConstructor;

/**
 * 판정 결과대로 댓글 응답을 만든다 (CM-05 · CM-08 · CM-20).
 *
 * <p><b>본문을 내려주는 경로가 모두 이 자리를 지나야 한다.</b> 도메인-모델링.md 「7.1 가시성과 권한」이 <i>"판정 지점을 하나로 모은다"</i> 고 정했고,
 * 판정 자체는 {@link CommentVisibilityPolicy} 하나뿐이지만 <b>「판정을 부르는 것을 잊지 않는 것」은 별개 문제다.</b> 조립을 여기 모아 두면
 * 응답을 만드는 길이 하나여서 잊을 자리가 없다.
 *
 * <p>열람할 수 없으면 본문만 빼고 나머지는 그대로 내린다 — 그것이 자리표시자다 (CM-08). 403 이 아니라 200 이다.
 *
 * <p><b>관리자 경로는 이곳을 쓰지 않는다.</b> 7.1 이 관리자를 매트릭스 밖에 두었고, 백오피스는 감사 로그를 남기는 전용 경로다 (CM-17 · AD-05).
 *
 * <p>Spring 빈으로 등록하지 않았다. 이 티켓에는 노출 엔드포인트가 없어 주입받을 곳이 없다 — 목록 조회(CM-06 · CM-07)가 배선을 정한다.
 */
@RequiredArgsConstructor
public class CommentItemAssembler {

  private final CommentVisibilityPolicy visibilityPolicy;

  /**
   * 댓글 한 건을 요청자에게 맞게 조립한다.
   *
   * @param author 작성자 블록. <b>이 조립기가 만들지 않고 받는다</b> — 닉네임과 아바타는 회원 쪽 데이터다
   * @param context 요청자 · 방장 · 부모 댓글 작성자. 대댓글이 아니면 부모 작성자는 null 이다
   */
  public CommentItemResponse assemble(
      Comment comment, CommentAuthorResponse author, CommentReadContext context) {

    boolean readable = visibilityPolicy.canReadContent(CommentReadTarget.of(comment), context);

    return new CommentItemResponse(
        comment.getId(),
        comment.getParentId(),
        comment.isSecret(),
        comment.getStatus(),
        readable ? comment.getContent() : null,
        CommentItemResponse.toKst(comment.getCreatedAt()),
        author);
  }
}
