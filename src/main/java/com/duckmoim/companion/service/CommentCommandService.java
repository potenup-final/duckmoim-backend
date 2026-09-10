package com.duckmoim.companion.service;

import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.common.service.NotificationOutboxPublisher;
import com.duckmoim.companion.domain.Comment;
import com.duckmoim.companion.domain.CompanionPost;
import com.duckmoim.companion.domain.PostStatus;
import com.duckmoim.companion.exception.CommentErrorCode;
import com.duckmoim.companion.exception.PostErrorCode;
import com.duckmoim.companion.infra.CommentRepository;
import com.duckmoim.companion.infra.CompanionPostRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 댓글 작성 (CM-01 · CM-02 · CM-03).
 *
 * <p><b>요청자가 실재하는 회원인지 여기서 보지 않는다.</b> API-설계.md 「1. 권한 등급」이 SIGNUP 판정을 관문 한 곳으로 모았고, STAR-41 의
 * SecurityConfig 가 이 경로를 그 등급으로 닫아 두었다 — 토큰이 없으면 401, 가입 미완료면 403 이라 여기까지 오지 않는다.
 *
 * <p><b>제재 중 유저 차단(I-14)은 아직 비어 있다.</b> 같은 관문의 일이고, docs/plans/인증-인가-구현-계획.md 가 그것을 D 티켓의 「포트 +
 * no-op 구현」으로 두고 실제 연결은 Safety 담당 티켓으로 미뤘다. 여기에 판정을 넣으면 그때 지워야 한다.
 */
@Service
@RequiredArgsConstructor
public class CommentCommandService {

  private final CommentRepository commentRepository;
  private final CompanionPostRepository companionPostRepository;
  private final NotificationOutboxPublisher outboxPublisher;

  /**
   * 댓글 본문을 고친다 (CM-09).
   *
   * <p>판정은 도메인이 한다 — 작성자 본인인지, 비밀 여부를 바꾸려는지. 여기서는 댓글을 찾아 넘기는 일만 한다.
   *
   * <p><b>모집글을 읽지 않는다.</b> 수정은 방장 권한이 아니라서 hostId 가 판정에 안 들어간다.
   */
  @Transactional
  public WrittenComment edit(CommentEditCommand command) {
    Comment comment = requireComment(command.commentId());

    comment.edit(command.requesterId(), command.content(), command.secret());

    return WrittenComment.from(comment);
  }

  /**
   * 댓글을 소프트 삭제한다 (CM-10 · CM-11).
   *
   * <p><b>모집글을 읽는 이유는 방장이 누구인지다.</b> 삭제는 작성자 또는 방장이 하고 (API-설계.md 「2-5. 댓글 (Companion)」), 방장은 남의
   * 애그리게이트에 있다.
   *
   * <p>하위 대댓글을 건드리지 않는다. 지운 댓글이 목록에 자리표시자로 남을지는 <b>조회 시점에 판정된다</b> (CM-11) — 하위가 있으면 남고 없으면 빠진다.
   * 여기서 대댓글까지 지우면 그 규칙이 무의미해지고 대댓글이 고아가 된다.
   */
  @Transactional
  public void delete(Long commentId, Long requesterId) {
    Comment comment = requireComment(commentId);
    CompanionPost post = requirePost(comment.getPostId());

    comment.deleteBy(requesterId, post.getHostId());
  }

  /** 소프트 삭제된 댓글도 여기서는 찾힌다. 조작을 막는 것은 도메인의 상태 가드다. */
  private Comment requireComment(Long commentId) {
    return commentRepository
        .findById(commentId)
        .orElseThrow(() -> new BusinessException(CommentErrorCode.COMMENT_NOT_FOUND));
  }

  /**
   * 모집글에 댓글이나 대댓글을 쓴다.
   *
   * <p><b>알림은 아웃박스에 적기만 한다</b> (NT-01). 발송은 워커가 트랜잭션 밖에서 하므로 (NT-02) 알림 채널이 죽어 있어도 이 작성은 실패하지 않는다 —
   * {@code I-25} 가 요구하는 것이 그것이고, 그래서 여기서 발송을 부르지 않는다.
   */
  @Transactional
  public WrittenComment write(CommentWriteCommand command) {
    CompanionPost post = requireOpenPost(command.postId());

    Comment saved = command.isReply() ? saveReply(command, post) : saveRoot(command, post);

    return WrittenComment.from(saved);
  }

  /**
   * 최상위 댓글을 쓰고 방장에게 보낼 알림을 적는다 (CM-01 · NT-06).
   *
   * <p>방장은 이미 읽어 둔 모집글이 알고 있어 조회가 늘지 않는다. 자기 글에 자기가 쓴 경우를 여기서 걸러내지 않는다 — 발행기가 판정한다.
   */
  private Comment saveRoot(CommentWriteCommand command, CompanionPost post) {
    Comment saved = commentRepository.save(rootOf(command));

    outboxPublisher.postCommented(
        post.getHostId(), command.authorId(), post.getId(), saved.getId());

    return saved;
  }

  /**
   * 대댓글을 쓰고 부모 댓글 작성자에게 보낼 알림을 적는다 (CM-02 · NT-06).
   *
   * <p><b>방장에게는 적지 않는다.</b> NT-06 이 「내 모집글에 댓글이 달렸다」와 「내 댓글에 답글이 달렸다」를 다른 종류로 갈랐고, 답글 한 건으로 둘을 만들면
   * 알림함이 같은 사실로 두 번 채워진다 (STAR-118 에서 정했다).
   */
  private Comment saveReply(CommentWriteCommand command, CompanionPost post) {
    Comment parent = requireActiveParent(command);
    Comment saved =
        commentRepository.save(
            parent.reply(command.authorId(), command.content(), command.secret()));

    outboxPublisher.commentReplied(
        parent.getAuthorId(), command.authorId(), post.getId(), saved.getId());

    return saved;
  }

  /**
   * 열린 글에만 쓸 수 있다 (CM-01).
   *
   * <p><b>락을 걸지 않는다.</b> 도메인-모델링.md 「3.1 경계와 트랜잭션 범위」가 <i>"방장이 마감하는 순간 이미 진행 중이던 작성 한 건이 닫힌 글에 들어올
   * 수 있다. 닫힌 글도 열람은 되고 사용자가 잃는 것이 없어 막지 않는다"</i> 고 정했다. 이 검사는 그래서 트랜잭션 밖의 읽기와 같은 성질이고, 비관적 락으로 조여서
   * 마감과 작성을 서로 기다리게 만들지 않는다.
   *
   * <p>모집글 행을 읽기만 한다. 댓글 수를 저장하지 않아 갱신할 것이 없고 (I-11), 그래서 동시 작성이 서로 기다리지 않는다.
   *
   * <p><b>읽은 글을 돌려준다.</b> 알림의 수신자가 방장이라 (NT-06) 부르는 쪽이 그 값을 필요로 한다 — 버리고 다시 읽으면 같은 행을 두 번 읽는다.
   */
  private CompanionPost requireOpenPost(Long postId) {
    CompanionPost post = requirePost(postId);

    if (post.getStatus() != PostStatus.OPEN) {
      throw new BusinessException(PostErrorCode.POST_ALREADY_CLOSED);
    }

    return post;
  }

  /**
   * 답글을 달 수 있는 부모 댓글을 찾는다 (CM-02).
   *
   * <p><b>세 경우가 모두 COMMENT_NOT_FOUND 404 다</b> — 없는 댓글, 다른 모집글의 댓글, 자리표시자로만 남은 댓글. API-컨벤션.md
   * 「Status Code 규칙」이 소프트 삭제된 리소스를 404 로 취급하라고 정했고, 이 경로에서 그 셋을 구분해 알려줄 이유가 없다. 지워진 댓글의 존재를 확인해 주는
   * 응답이 되어서도 안 된다.
   *
   * <p>깊이 판정은 여기 없다. 부모 댓글이 스스로 한다 (I-06).
   *
   * <p><b>부모를 찾아서 돌려주기만 한다.</b> 답글을 만드는 것은 부르는 쪽이다 — 알림의 수신자가 부모 댓글 작성자라 (NT-06) 부모가 밖에서 필요하고, 여기서
   * 답글까지 만들어 반환하면 그 값이 가려진다.
   */
  private Comment requireActiveParent(CommentWriteCommand command) {
    Comment parent =
        commentRepository
            .findById(command.parentId())
            .orElseThrow(() -> new BusinessException(CommentErrorCode.COMMENT_NOT_FOUND));

    if (!parent.belongsTo(command.postId()) || !parent.isActive()) {
      throw new BusinessException(CommentErrorCode.COMMENT_NOT_FOUND);
    }

    return parent;
  }

  private CompanionPost requirePost(Long postId) {
    return companionPostRepository
        .findById(postId)
        .orElseThrow(() -> new BusinessException(PostErrorCode.POST_NOT_FOUND));
  }

  private Comment rootOf(CommentWriteCommand command) {
    return Comment.root(command.postId(), command.authorId(), command.content(), command.secret());
  }
}
