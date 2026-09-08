package com.duckmoim.companion.service;

import com.duckmoim.admin.domain.AuditKind;
import com.duckmoim.admin.service.AuditLogRecorder;
import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.companion.domain.Comment;
import com.duckmoim.companion.exception.CommentErrorCode;
import com.duckmoim.companion.infra.CommentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자가 댓글에 내리는 조치 (AD-07).
 *
 * <p><b>admin 패키지에 두지 않는다.</b> 경로는 {@code /api/v1/admin/**} 이지만 바꾸는 것은 {@code Comment} 애그리게이트이고,
 * STAR-79 가 의존 방향을 「각 컨텍스트 → admin」 으로 합의했다. admin 에 두면 그 방향이 뒤집힌다.
 *
 * <p>제재(AD-04)가 유저 축이라면 이쪽은 콘텐츠 축이다. 같은 신고 하나에서 두 조치가 나올 수 있어 서로를 부르지 않는다.
 */
@Service
@RequiredArgsConstructor
public class AdminCommentCommandService {

  private final CommentRepository commentRepository;
  private final AuditLogRecorder auditLogRecorder;

  /**
   * 댓글을 가린다 (AD-07).
   *
   * <p><b>전이를 먼저 하고 기록한다.</b> 순서가 반대이면 금지된 전이(409)에도 기록이 남아, 일어나지 않은 조치가 장부에 오른다 — 감사 로그는 고칠 수 없다
   * ({@code I-13}).
   *
   * <p><b>기록이 전이와 같은 트랜잭션이다.</b> {@code AuditLogRecorder} 가 부르는 쪽에 요구하는 것이고, {@code REQUIRES_NEW} 로
   * 떼면 전이가 실패해도 기록만 남는다.
   *
   * <p><b>하위 대댓글을 건드리지 않는다.</b> 가린 댓글이 목록에 자리표시자로 남을지는 조회 시점에 판정된다 (CM-11) — 삭제와 같은 이유로, 여기서 대댓글까지
   * 손대면 그 규칙이 무의미해지고 대댓글이 고아가 된다.
   *
   * <p><b>신고({@code Report})를 손대지 않는다.</b> 신고 처리 상태 전이와 이력은 AD-03 소관이고 그 이력은 {@code Report} 자신이 진다
   * (도메인-모델링.md 「1. 유비쿼터스 언어」). 여기서 함께 바꾸면 같은 사실이 두 곳에서 움직인다.
   */
  @Transactional
  public void blind(Long commentId, Long adminUserId) {
    Comment comment = requireComment(commentId);

    comment.blind();

    auditLogRecorder.record(adminUserId, AuditKind.BLIND, commentId, "댓글 블라인드");
  }

  /**
   * 소프트 삭제·블라인드된 댓글도 여기서는 찾힌다. 조작을 막는 것은 도메인의 상태 가드다 ({@code COMMENT_NOT_ACTIVE} 409).
   *
   * <p>{@code CommentCommandService} 와 같은 판단이다 — 저장소에서 걸러 404 로 만들면 이미 가려진 것과 없는 것이 구분되지 않는다.
   */
  private Comment requireComment(Long commentId) {
    return commentRepository
        .findById(commentId)
        .orElseThrow(() -> new BusinessException(CommentErrorCode.COMMENT_NOT_FOUND));
  }
}
