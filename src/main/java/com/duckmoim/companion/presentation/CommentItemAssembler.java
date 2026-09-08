package com.duckmoim.companion.presentation;

import com.duckmoim.companion.domain.Comment;
import com.duckmoim.companion.domain.CommentActionPolicy;
import com.duckmoim.companion.domain.CommentReadContext;
import com.duckmoim.companion.domain.CommentReadTarget;
import com.duckmoim.companion.domain.CommentVisibilityPolicy;
import com.duckmoim.companion.service.CommentView;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

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
 * <p>Spring 빈이다. 판정기 둘은 상태가 없어 {@link com.duckmoim.companion.config.CommentPolicyConfig} 가 한 번 만들어
 * 넣는다 — domain 은 프레임워크에 묶이지 않아 스스로 빈이 될 수 없다.
 */
@Component
@RequiredArgsConstructor
public class CommentItemAssembler {

  private final CommentVisibilityPolicy visibilityPolicy;
  private final CommentActionPolicy actionPolicy;

  /**
   * 댓글 한 건을 요청자에게 맞게 조립한다.
   *
   * @param author 작성자 블록. <b>이 조립기가 만들지 않고 받는다</b> — 닉네임과 아바타는 회원 쪽 데이터다
   * @param context 요청자 · 방장 · 부모 댓글 작성자. 대댓글이 아니면 부모 작성자는 null 이다
   */
  public CommentItemResponse assemble(
      Comment comment, CommentAuthorResponse author, CommentReadContext context) {
    return assemble(comment, author, context, List.of());
  }

  /**
   * 루트 댓글을 대댓글과 함께 조립한다 (CM-06).
   *
   * <p>대댓글의 판정 맥락은 루트와 다르다 — <b>부모 댓글 작성자가 채워진다.</b> 비밀 대댓글은 그 사람도 본문을 볼 수 있고 (도메인 7.1), 대댓글에는 답글
   * 버튼이 붙지 않는다 (CM-18).
   */
  public CommentItemResponse assembleWithReplies(CommentView root, CommentReadContext context) {
    List<CommentItemResponse> replies =
        root.replies().stream()
            .map(reply -> assemble(reply, repliedContext(root, context), List.of()))
            .toList();

    return assemble(root, context, replies);
  }

  private CommentItemResponse assemble(
      CommentView view, CommentReadContext context, List<CommentItemResponse> replies) {
    return assemble(view.comment(), CommentAuthorResponse.from(view), context, replies);
  }

  private CommentItemResponse assemble(
      Comment comment,
      CommentAuthorResponse author,
      CommentReadContext context,
      List<CommentItemResponse> replies) {

    CommentReadTarget target = CommentReadTarget.of(comment);
    boolean readable = visibilityPolicy.canReadContent(target, context);

    return new CommentItemResponse(
        comment.getId(),
        comment.getParentId(),
        comment.isSecret(),
        comment.getStatus(),
        readable ? comment.getContent() : null,
        CommentItemResponse.toKst(comment.getCreatedAt()),
        author,
        actionPolicy.availableActions(target, context),
        replies);
  }

  /** 부모 댓글 작성자가 있다는 것이 곧 「이것은 대댓글이다」 라는 뜻이다 (CommentReadContext). */
  private static CommentReadContext repliedContext(CommentView root, CommentReadContext context) {
    return new CommentReadContext(
        context.requesterId(), context.hostId(), root.comment().getAuthorId());
  }
}
