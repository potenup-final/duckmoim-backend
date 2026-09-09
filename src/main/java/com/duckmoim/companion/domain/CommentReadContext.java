package com.duckmoim.companion.domain;

/**
 * 본문 열람 판정의 <b>요청자 쪽</b> 입력 (도메인-모델링.md 「7.1 가시성과 권한」).
 *
 * <p>7.1 이 <i>"판정 입력은 ID 네 개뿐이다 — (요청자, 방장, 댓글 작성자, 부모 댓글 작성자)"</i> 라고 정했다. 그중 댓글 작성자는 댓글 자신에서 나오므로
 * ({@link CommentReadTarget}) 여기 담기는 것은 셋이다.
 *
 * <p><b>S3 에서 확정 멤버 여부가 다섯 번째 입력으로 들어온다.</b> 그때 이 레코드에 필드가 붙는다.
 *
 * @param requesterId 요청자. <b>비회원이면 null 이다</b> — 비회원도 공개 댓글 본문을 본다 (CM-20)
 * @param hostId 모집글 방장. 비밀 댓글 본문을 볼 수 있는 두 번째 사람이다
 * @param parentAuthorId 부모 댓글 작성자. <b>대댓글일 때만 채운다.</b> 루트 댓글에 값을 주면 그 사람에게 본문이 열린다 — 루트 댓글에는 부모가
 *     없으므로 null 이어야 한다
 */
public record CommentReadContext(Long requesterId, Long hostId, Long parentAuthorId) {

  /** 인증 없이 들어온 요청 (CM-20). */
  public static CommentReadContext ofGuest(Long hostId, Long parentAuthorId) {
    return new CommentReadContext(null, hostId, parentAuthorId);
  }

  /**
   * 요청자가 이 ID 의 사람인지 본다.
   *
   * <p>두 가지를 함께 막는다. <b>비회원({@code requesterId == null})은 누구와도 같지 않고</b>, 비교 대상이 null 인 경우도 같지 않다 —
   * 루트 댓글의 부모 작성자가 null 인데 비회원이 물으면 null == null 로 통과해 버린다.
   */
  public boolean isRequester(Long userId) {
    return requesterId != null && requesterId.equals(userId);
  }
}
