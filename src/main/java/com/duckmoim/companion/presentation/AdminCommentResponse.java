package com.duckmoim.companion.presentation;

import com.duckmoim.companion.domain.Comment;
import com.duckmoim.companion.domain.CommentStatus;
import com.duckmoim.companion.service.AdminCommentView;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 관리자가 열어 본 댓글 한 건 (CM-17).
 *
 * <p><b>이 경로의 응답 모양은 문서에 없다.</b> 화면 계약이 백오피스의 다른 경로는 예시를 갖고 있는데 여기만 비어서, 조회 응답인 {@link
 * CommentItemResponse} 에서 관리자에게 의미 없는 것을 덜어내 정했다.
 *
 * <p><b>{@code content} 에 {@code @JsonInclude} 를 붙이지 않는다.</b> 그쪽에서 그 어노테이션이 하는 일 — 열람 권한이 없으면 키를
 * 제거하는 것 (CM-05 · I-07) — 이 여기서는 정확히 반대다. 관리자는 가시성 매트릭스 밖이고 (도메인-모델링.md 「7. 도메인 규칙」), 본문이 사라지는 분기가
 * 있으면 이 엔드포인트가 있을 이유가 없다.
 *
 * <p><b>{@code availableActions} 를 싣지 않는다.</b> CM-18 은 일반 사용자 화면의 버튼 노출이고, 백오피스가 이 댓글에 할 수 있는 일은
 * 블라인드(AD-07)라 소관이 다르다. <b>{@code replies} 도 없다</b> — 한 건 조회다.
 *
 * @param postId 신고 목록(AD-02) 응답에 모집글이 없어, 관리자가 어느 글의 댓글인지 아는 통로가 여기뿐이다
 * @param status 지운 댓글도 내려주므로 (API-설계.md 「2-5. 댓글 (Companion)」) 어떤 상태의 본문을 보고 있는지 함께 준다
 * @param createdAt 저장은 UTC 이고 응답은 KST 오프셋을 포함한다 (API-컨벤션.md 「필드 표기 규칙」)
 */
public record AdminCommentResponse(
    Long id,
    Long postId,
    Long parentId,
    boolean secret,
    CommentStatus status,
    String content,
    OffsetDateTime createdAt,
    CommentAuthorResponse author) {

  private static final ZoneId KST = ZoneId.of("Asia/Seoul");

  static AdminCommentResponse from(AdminCommentView view) {
    Comment comment = view.comment();

    return new AdminCommentResponse(
        comment.getId(),
        comment.getPostId(),
        comment.getParentId(),
        comment.isSecret(),
        comment.getStatus(),
        comment.getContent(),
        toKst(comment.getCreatedAt()),
        author(view));
  }

  private static CommentAuthorResponse author(AdminCommentView view) {
    return new CommentAuthorResponse(
        view.comment().getAuthorId(), view.nickname(), view.profileImageUrl(), view.lastSeen());
  }

  private static OffsetDateTime toKst(LocalDateTime storedInUtc) {
    return storedInUtc.atOffset(ZoneOffset.UTC).atZoneSameInstant(KST).toOffsetDateTime();
  }
}
