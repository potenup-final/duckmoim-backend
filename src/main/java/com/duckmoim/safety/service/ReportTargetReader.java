package com.duckmoim.safety.service;

import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.common.exception.ErrorCode;
import com.duckmoim.companion.exception.CommentErrorCode;
import com.duckmoim.companion.exception.PostErrorCode;
import com.duckmoim.companion.infra.CommentRepository;
import com.duckmoim.companion.infra.CompanionPostRepository;
import com.duckmoim.identity.domain.SignupStatus;
import com.duckmoim.identity.exception.UserErrorCode;
import com.duckmoim.identity.infra.UserRepository;
import com.duckmoim.safety.domain.ReportTargetType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 신고 대상이 실재하는지 확인한다.
 *
 * <p><b>세 대상의 검사가 한 자리에 있는 이유</b> — 티켓 설명은 이 일을 댓글 신고(STAR-60)에 뒀지만, 그러면 유저(SF-01) · 모집글(SF-02)
 * 담당자가 각자 붙여 <b>같은 검사가 세 곳에 생긴다.</b> 이 티켓이 존재하는 이유가 그것을 막는 것이다.
 *
 * <p><b>404 를 대상별로 낸다.</b> {@code USER_NOT_FOUND} · {@code POST_NOT_FOUND} · {@code
 * COMMENT_NOT_FOUND} 가 정본에 이미 있다. 신고 쪽에 같은 뜻의 코드를 또 만들면 클라이언트가 대상에 따라 다른 이름을 받는다.
 *
 * <p><b>대상마다 「없다」의 뜻이 다르다.</b>
 *
 * <ul>
 *   <li><b>유저</b> — 탈퇴한 회원은 없는 것으로 본다. 정본이 {@code USER_NOT_FOUND} 의 근거를 「탈퇴 포함」으로 적었다. {@code
 *       UserRepository} 가 <i>"조회 메서드를 늘리지 않는다"</i> 고 못박아 두어 상속 메서드로 읽고 상태는 여기서 본다
 *   <li><b>모집글</b> — 삭제가 없다 (결정 D-3 · ADR 0002). 있으면 있는 것이다
 *   <li><b>댓글</b> — 소프트 삭제·블라인드된 댓글에 대한 신고도 <b>접수한다.</b> CM-14 의 「확정 후 반영」 항목이었고 STAR-60 이 정했다. 막으면
 *       지우고 도망가는 길이 생기고, 신고의 실제 조치는 댓글 삭제가 아니라 <b>유저 제재</b>({@code I-14})로 가므로 댓글이 없어진 뒤에도 접수가
 *       생산적이다. 본문은 소프트 삭제라 DB 에 남아 백오피스가 판단할 재료가 된다 (CM-17)
 * </ul>
 *
 * <p><b>댓글 쪽이 「소프트 삭제된 리소스는 404 로 취급한다」 와 어긋나 보이는 자리다.</b> 그 규칙은 리소스를 <i>조회하는</i> 경로에 대한 것이고, 신고 대상
 * 참조는 조회가 아니라고 보았다 — 이 경로는 댓글을 응답으로 내려주지 않는다. 같은 이유로 {@code existsById} 로 충분하고 상태를 읽지 않는다.
 */
@Component
@RequiredArgsConstructor
public class ReportTargetReader {

  private final UserRepository userRepository;
  private final CompanionPostRepository companionPostRepository;
  private final CommentRepository commentRepository;

  public void requireExists(ReportTargetType targetType, Long targetId) {
    if (!exists(targetType, targetId)) {
      throw new BusinessException(notFoundOf(targetType));
    }
  }

  private boolean exists(ReportTargetType targetType, Long targetId) {
    return switch (targetType) {
      case USER -> activeUserExists(targetId);
      case POST -> companionPostRepository.existsById(targetId);
      case COMMENT -> commentRepository.existsById(targetId);
    };
  }

  private boolean activeUserExists(Long targetId) {
    return userRepository
        .findById(targetId)
        .filter(user -> user.getStatus() != SignupStatus.WITHDRAWN)
        .isPresent();
  }

  private static ErrorCode notFoundOf(ReportTargetType targetType) {
    return switch (targetType) {
      case USER -> UserErrorCode.USER_NOT_FOUND;
      case POST -> PostErrorCode.POST_NOT_FOUND;
      case COMMENT -> CommentErrorCode.COMMENT_NOT_FOUND;
    };
  }
}
