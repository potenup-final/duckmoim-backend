package com.duckmoim.companion.service;

import com.duckmoim.common.exception.BusinessException;
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
 * <p><b>요청자가 실재하는 회원인지 여기서 보지 않는다.</b> API-설계.md 「1. 권한 등급」이 SIGNUP 판정을 인터셉터 한 곳으로 모았고, 제재 중 유저
 * 차단(I-14)도 같은 자리다. 인증이 아직 없어 그 확인이 실제로는 비어 있다 — 임시 헤더를 걷어낼 때 함께 닫힌다.
 */
@Service
@RequiredArgsConstructor
public class CommentCommandService {

  private final CommentRepository commentRepository;
  private final CompanionPostRepository companionPostRepository;

  /** 모집글에 댓글이나 대댓글을 쓴다. */
  @Transactional
  public WrittenComment write(CommentWriteCommand command) {
    requireOpenPost(command.postId());

    Comment comment = command.isReply() ? replyTo(command) : rootOf(command);

    return WrittenComment.from(commentRepository.save(comment));
  }

  /**
   * 열린 글에만 쓸 수 있다 (CM-01).
   *
   * <p><b>락을 걸지 않는다.</b> 도메인-모델링.md 「3.1 경계와 트랜잭션 범위」가 <i>"방장이 마감하는 순간 이미 진행 중이던 작성 한 건이 닫힌 글에 들어올
   * 수 있다. 닫힌 글도 열람은 되고 사용자가 잃는 것이 없어 막지 않는다"</i> 고 정했다. 이 검사는 그래서 트랜잭션 밖의 읽기와 같은 성질이고, 비관적 락으로 조여서
   * 마감과 작성을 서로 기다리게 만들지 않는다.
   *
   * <p>모집글 행을 읽기만 한다. 댓글 수를 저장하지 않아 갱신할 것이 없고 (I-11), 그래서 동시 작성이 서로 기다리지 않는다.
   */
  private void requireOpenPost(Long postId) {
    CompanionPost post =
        companionPostRepository
            .findById(postId)
            .orElseThrow(() -> new BusinessException(PostErrorCode.POST_NOT_FOUND));

    if (post.getStatus() != PostStatus.OPEN) {
      throw new BusinessException(PostErrorCode.POST_ALREADY_CLOSED);
    }
  }

  /**
   * 부모를 찾아 대댓글을 만든다 (CM-02).
   *
   * <p><b>세 경우가 모두 COMMENT_NOT_FOUND 404 다</b> — 없는 댓글, 다른 모집글의 댓글, 자리표시자로만 남은 댓글. API-컨벤션.md
   * 「Status Code 규칙」이 소프트 삭제된 리소스를 404 로 취급하라고 정했고, 이 경로에서 그 셋을 구분해 알려줄 이유가 없다. 지워진 댓글의 존재를 확인해 주는
   * 응답이 되어서도 안 된다.
   *
   * <p>깊이 판정은 여기 없다. 부모 댓글이 스스로 한다 (I-06).
   */
  private Comment replyTo(CommentWriteCommand command) {
    Comment parent =
        commentRepository
            .findById(command.parentId())
            .orElseThrow(() -> new BusinessException(CommentErrorCode.COMMENT_NOT_FOUND));

    if (!parent.belongsTo(command.postId()) || !parent.isActive()) {
      throw new BusinessException(CommentErrorCode.COMMENT_NOT_FOUND);
    }

    return parent.reply(command.authorId(), command.content(), command.secret());
  }

  private Comment rootOf(CommentWriteCommand command) {
    return Comment.root(command.postId(), command.authorId(), command.content(), command.secret());
  }
}
