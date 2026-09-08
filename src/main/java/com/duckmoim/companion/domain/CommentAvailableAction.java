package com.duckmoim.companion.domain;

/**
 * 댓글에 붙는 액션 버튼 (CM-18. API-설계.md 「2-5. 댓글 (Companion)」이 넷으로 정했다).
 *
 * <p><b>서버가 채운다.</b> 화면-계약.md 가 이유를 적어 두었다 — 프론트에 같은 판정이 {@code lib/comment-perm.ts} 에 있는데 <i>"API
 * 가 붙는 날 지운다. 두 곳에서 판정하면 갈라진다."</i>
 */
public enum CommentAvailableAction {
  /** 루트 댓글에만. 깊이 1단계 고정 (CM-02). */
  REPLY,
  /** 작성자 본인 (CM-09). */
  EDIT,
  /** 작성자 또는 방장 (CM-10). */
  DELETE,
  /** 남의 댓글에만. 본인 댓글에는 안 붙는다. */
  REPORT
}
