package com.duckmoim.companion.domain;

/**
 * 댓글의 상태 (도메인-모델링.md 「6. 라이프사이클」).
 *
 * <p>ACTIVE 에서만 출발하고 되돌아오는 전이가 없다. 작성자가 지우면 DELETED, 신고 처리 결과로 가려지면 BLINDED 다.
 *
 * <p><b>소프트 삭제를 별도 시각 컬럼이 아니라 이 값으로 표현한다.</b> 도메인 4장이 소프트 삭제 대상을 User 와 Comment 둘로 한정했고, 삭제된 댓글은
 * 사라지는 것이 아니라 자리표시자로 남아야 해서 (CM-11) 어차피 상태가 필요하다.
 */
public enum CommentStatus {
  ACTIVE,
  DELETED,
  BLINDED
}
