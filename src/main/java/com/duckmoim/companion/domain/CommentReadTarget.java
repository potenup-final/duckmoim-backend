package com.duckmoim.companion.domain;

/**
 * 본문 열람 판정의 <b>대상 쪽</b> 입력 — 판정에 쓰이는 댓글의 사실 셋 (도메인-모델링.md 「7.1 가시성과 권한」).
 *
 * <p><b>{@link Comment} 를 그대로 넘기지 않고 사실만 뽑는 이유가 둘이다.</b>
 *
 * <ul>
 *   <li>판정에 쓰이는 것이 셋뿐임을 시그니처가 드러낸다. 본문 · 모집글 · 시각은 판정과 무관하다
 *   <li><b>단위 테스트가 전 조합을 만들 수 있다.</b> {@code DELETED} · {@code BLINDED} 댓글을 만드는 경로가 아직 없다 — 삭제는
 *       CM-10, 블라인드는 신고 처리 소관이다. 엔티티를 받으면 세 상태 중 하나만 검증하게 된다
 * </ul>
 *
 * @param authorId 댓글 작성자. 비밀 댓글 본문을 볼 수 있는 첫 번째 사람이다
 * @param secret 비밀 댓글 여부 (CM-03)
 * @param status {@code ACTIVE} 가 아니면 아무도 본문을 못 본다 (CM-08 · CM-11)
 */
public record CommentReadTarget(Long authorId, boolean secret, CommentStatus status) {

  public static CommentReadTarget of(Comment comment) {
    return new CommentReadTarget(comment.getAuthorId(), comment.isSecret(), comment.getStatus());
  }
}
