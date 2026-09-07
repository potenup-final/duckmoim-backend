package com.duckmoim.companion.service;

import com.duckmoim.companion.domain.Comment;
import com.duckmoim.companion.domain.CommentStatus;
import java.time.LocalDateTime;

/**
 * 방금 쓴 댓글. 작성 유스케이스의 결과다.
 *
 * <p><b>엔티티를 그대로 올리지 않는다.</b> EventSummary 와 같은 자리이고, 이유도 같다 — 유스케이스가 무엇을 돌려주기로 약속했는지가 엔티티의 전체 모양에
 * 가려지면 안 된다.
 *
 * <p>작성 응답에 담는 것이 이 여섯뿐인 이유는 API-설계.md 「2-5. 댓글 (Companion)」에 있다. availableActions 와 작성자 블록은 요청자에
 * 따라 달라지는 판정이 필요하고, 도메인-모델링.md 「7.1 가시성과 권한」이 그 판정 지점을 한 곳으로 모으라고 정했다. 조회 티켓이 그 한 곳을 만든다.
 *
 * @param createdAt 저장된 값 그대로 UTC 다. KST 오프셋을 붙이는 것은 응답 조립의 일이다
 */
public record WrittenComment(
    Long id,
    Long parentId,
    boolean secret,
    CommentStatus status,
    String content,
    LocalDateTime createdAt) {

  static WrittenComment from(Comment comment) {
    return new WrittenComment(
        comment.getId(),
        comment.getParentId(),
        comment.isSecret(),
        comment.getStatus(),
        comment.getContent(),
        comment.getCreatedAt());
  }
}
