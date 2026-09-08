package com.duckmoim.companion.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 댓글마다 어떤 액션 버튼이 붙는지 판정한다 (CM-18).
 *
 * <p>판정 입력이 {@link CommentVisibilityPolicy} 와 같다 — 요청자 · 방장 · 댓글 작성자, 그리고 댓글이 루트인지. 그래서 {@link
 * CommentReadContext} 를 그대로 쓴다.
 *
 * <p><b>{@code status} 가 ACTIVE 가 아니면 배열이 빈다.</b> API 설계가 <i>"지운 댓글에 답글이 달리면 안 된다"</i> 고 정했고, 답글뿐
 * 아니라 수정 · 삭제 · 신고도 마찬가지다. 자리표시자는 존재만 남은 것이지 조작 대상이 아니다.
 *
 * <p><b>비회원에게는 아무것도 붙지 않는다.</b> 넷 다 로그인해야 할 수 있는 일이고, 요청자가 없으면 「본인」도 「남」도 성립하지 않는다.
 *
 * <p><b>EDIT 과 DELETE 의 엔드포인트는 아직 없다</b> (CM-09 · CM-10). 그래도 계약대로 붙인다 — 서버가 판정을 미루면 프론트가 자체 판정을
 * 만들고, 화면 계약이 {@code lib/comment-perm.ts} 를 지우라고 한 이유가 정확히 그것이다.
 *
 * <p>상태를 갖지 않는 도메인 서비스다.
 */
public final class CommentActionPolicy {

  public List<CommentAvailableAction> availableActions(
      CommentReadTarget target, CommentReadContext context) {

    List<CommentAvailableAction> actions = new ArrayList<>();

    if (target.status() != CommentStatus.ACTIVE || context.requesterId() == null) {
      return actions;
    }

    boolean isAuthor = context.isRequester(target.authorId());

    // 부모 작성자가 없다는 것이 곧 루트 댓글이라는 뜻이다 (CommentReadContext).
    if (context.parentAuthorId() == null) {
      actions.add(CommentAvailableAction.REPLY);
    }
    if (isAuthor) {
      actions.add(CommentAvailableAction.EDIT);
    }
    if (isAuthor || context.isRequester(context.hostId())) {
      actions.add(CommentAvailableAction.DELETE);
    }
    if (!isAuthor) {
      actions.add(CommentAvailableAction.REPORT);
    }

    return actions;
  }
}
