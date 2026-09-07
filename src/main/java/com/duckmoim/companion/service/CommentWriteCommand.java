package com.duckmoim.companion.service;

/**
 * 댓글 작성 요청을 유스케이스의 어휘로 옮긴 것 (CM-01 · CM-02 · CM-03).
 *
 * <p>요청 DTO 를 그대로 받지 않는다 (아키텍처-컨벤션.md 「service」). authorId 는 요청 본문이 아니라 인증에서 나온다 — 남의 이름으로 쓰는 것을 막는
 * 유일한 장치다.
 *
 * @param parentId 있으면 대댓글이다. 대댓글은 별도 타입이 아니다 (도메인-모델링.md 「1. 유비쿼터스 언어」)
 */
public record CommentWriteCommand(
    Long postId, Long authorId, Long parentId, String content, boolean secret) {

  public boolean isReply() {
    return parentId != null;
  }
}
