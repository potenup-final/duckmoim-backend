package com.duckmoim.companion.service;

import com.duckmoim.admin.domain.AuditKind;
import com.duckmoim.admin.service.AuditLogRecorder;
import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.companion.exception.CommentErrorCode;
import com.duckmoim.companion.infra.AuthoredComment;
import com.duckmoim.companion.infra.CommentRepository;
import com.duckmoim.identity.domain.AuthorDisplay;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자가 댓글 본문을 열어 본다 (CM-17 · AD-05).
 *
 * <p><b>본문을 내리는 것과 연 사실을 남기는 것이 한 동작이다.</b> 채팅이 없어 비밀 댓글이 참여자끼리 연락처를 주고받는 유일한 통로라, 관리자가 그것을 여는 것은
 * 남의 연락처를 들여다보는 일이다. 개인정보처리방침 제8조가 「열람 사실은 수정·삭제할 수 없는 기록으로 남습니다」라고 이미 공개돼 있다.
 *
 * <p><b>admin 패키지에 두지 않는다.</b> 경로는 {@code /api/v1/admin/**} 이지만 읽는 것은 {@code Comment} 애그리게이트이고,
 * STAR-79 가 의존 방향을 <b>「각 컨텍스트 → admin」</b> 으로 합의했다. 여기를 admin 에 두면 그 방향이 뒤집힌다.
 *
 * <p><b>가시성 매트릭스를 지나지 않는 유일한 경로다</b> (도메인-모델링.md 「7. 도메인 규칙」). 관리자는 그 표 밖이라 {@code secret} 여부로 갈라지지
 * 않는다.
 *
 * <p><b>탈퇴 익명화(AU-11)에는 관리자도 예외가 아니다.</b> 매트릭스 밖이라고 여기만 실명을 내릴 수 있는 것이 아니라, {@code withdraw} 가 닉네임
 * 컬럼을 이미 비워 <b>내릴 실명 자체가 없다.</b> 예외를 두면 얻는 것 없이 조립 경로만 갈라진다.
 */
@Service
@RequiredArgsConstructor
public class AdminCommentReadService {

  private final CommentRepository commentRepository;
  private final AuditLogRecorder auditLogRecorder;
  private final Clock clock;

  /**
   * 댓글 한 건을 열어 보고 그 사실을 남긴다.
   *
   * <p><b>기록이 열람과 같은 트랜잭션이다.</b> {@code AuditLogRecorder} 가 부르는 쪽에 요구하는 것이고 (I-13), 떼어 두면 열람이 실패한
   * 뒤에도 기록만 남아 일어나지 않은 일이 장부에 오른다. 그래서 {@code readOnly} 가 아니다 — 이 경로는 읽기만 하는 것이 아니다.
   *
   * <p><b>없는 댓글에는 기록을 남기지 않는다.</b> 먼저 읽고 그 다음에 기록한다 — 순서가 반대이면 존재하지 않는 댓글을 열어봤다는 줄이 남고, 감사 로그는 고칠 수
   * 없다.
   *
   * <p><b>공개 댓글을 열어도 남긴다.</b> API-설계.md 「2-7. 백오피스 (Admin)」가 이 경로를 「호출마다 감사 로그」로 정했다. 글자 그대로 지키는
   * 이유는 덜 남기는 쪽의 실수를 되돌릴 수 없기 때문이고, {@code AuditKind} 가 다섯으로 고정이라 따로 만들 값도 없다.
   *
   * @param reportId 어느 신고를 처리하다 열었는지. 화면-계약.md 가 이 호출을 「신고 목록에서 본문 보기를 누를 때」로 정해 두었다. 없어도 된다
   */
  @Transactional
  public AdminCommentView read(Long commentId, Long adminUserId, Long reportId) {
    AuthoredComment authored =
        commentRepository
            .findAuthoredById(commentId)
            .orElseThrow(() -> new BusinessException(CommentErrorCode.COMMENT_NOT_FOUND));

    auditLogRecorder.record(adminUserId, AuditKind.SECRET_READ, commentId, detailOf(reportId));

    AuthorDisplay author =
        AuthorDisplay.of(
            authored.authorStatus(),
            authored.nickname(),
            authored.profileImageUrl(),
            authored.lastSeenAt(),
            clock);

    return new AdminCommentView(
        authored.comment(), author.nickname(), author.profileImageUrl(), author.lastSeen());
  }

  /** 본문을 남기지 않는다. 감사 로그는 무엇을 열었는지의 기록이지 그 내용의 사본이 아니다. */
  private static String detailOf(Long reportId) {
    if (reportId == null) {
      return "댓글 본문 열람";
    }

    return "신고 %d 처리 중 댓글 본문 열람".formatted(reportId);
  }
}
