package com.duckmoim.companion.domain;

/**
 * 댓글 본문을 요청자에게 보여줄 수 있는지 판정한다 (CM-04 · CM-05 · CM-20).
 *
 * <p><b>이 프로젝트에서 본문 열람을 판정하는 유일한 자리다.</b> 도메인-모델링.md 「7.1 가시성과 권한」이 <i>"판정 지점을 하나로 모은다. 본문을 반환하는
 * 경로가 목록·상세·내 활동 내역·백오피스로 여럿이고, 하나만 빠뜨리면 규칙이 무의미하다"</i> 고 정했다. 경로가 늘어도 판정은 여기 한 번만 있어야 한다.
 *
 * <p>판정 결과가 <b>403 이 되지 않는다.</b> 열람할 수 없으면 본문 키를 빼고 200 으로 내린다 — 그것이 자리표시자다 (CM-08). 그래서 이 클래스는 예외를
 * 던지지 않는다.
 *
 * <p><b>관리자는 이 판정을 지나지 않는다.</b> 7.1 이 <i>"관리자는 이 표 밖이다"</i> 라고 못박았고, 백오피스는 감사 로그를 남기는 전용 경로로 비밀 댓글
 * 본문을 본다 (CM-17 · AD-05). 여기에 관리자 분기를 넣으면 그 감사 로그를 우회하는 문이 생긴다.
 *
 * <p>상태를 갖지 않는 도메인 서비스다 (아키텍처-컨벤션.md 「패키지 구조」).
 */
public final class CommentVisibilityPolicy {

  /**
   * 본문을 보여줄 수 있으면 true.
   *
   * <p>판정 순서가 곧 7.1 매트릭스의 행 순서다.
   *
   * <ul>
   *   <li>삭제 · 블라인드 댓글은 <b>누구에게도</b> 본문이 없다. 작성자 본인도 못 본다
   *   <li>공개 댓글은 비회원까지 전부 본다 (CM-20)
   *   <li>비밀 댓글은 작성자 본인 · 방장, 그리고 대댓글이면 부모 댓글 작성자
   * </ul>
   *
   * <p><b>부모 댓글이 비밀인지 공개인지는 판정에 영향을 주지 않는다.</b> 7.1 이 명시적으로 정한 것이고, 「부모가 비밀이면 더 좁게」가 자연스러워 보이지만
   * 아니다.
   */
  public boolean canReadContent(CommentReadTarget target, CommentReadContext context) {
    if (target.status() != CommentStatus.ACTIVE) {
      return false;
    }
    if (!target.secret()) {
      return true;
    }

    return context.isRequester(target.authorId())
        || context.isRequester(context.hostId())
        || context.isRequester(context.parentAuthorId());
  }
}
