package com.duckmoim.companion.infra;

import com.duckmoim.companion.domain.Comment;
import com.duckmoim.companion.domain.CommentStatus;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * <b>조회 메서드를 두지 않는다.</b> 작성 경로가 저장소에 묻는 것은 「부모 댓글 한 건」뿐이고 그것은 findById 다.
 *
 * <p>목록 조회(CM-06 · CM-07)는 루트 기준 커서와 부모에 딸린 대댓글 묶음이 필요해 전용 메서드가 생긴다. 그 모양은 정렬 키를 쥔 그 티켓이 정한다 — 여기서
 * 미리 지어내면 쓰지 않는 메서드가 남고, 그 메서드가 다음 담당의 기준선이 된다.
 *
 * <p><b>여기 하나만 예외다.</b> 채팅 초대가 「댓글을 쓴 사람인가」를 묻는다 (CH-02). 위 문단이 미룬 것은 <b>목록</b>이고 이것은 목록이 아니라 존재
 * 여부라, 정렬 키도 커서도 필요 없어 뒤에 올 티켓과 겹치지 않는다.
 *
 * <p><b>커스텀 프래그먼트가 둘이다.</b> 정렬 방향과 조인 대상이 갈려 한 인터페이스에 담기지 않는다 — 목록은 작성 시간 오름차순으로 작성자를 붙이고, 내 내역은
 * 최신순으로 모집글을 붙인다 (CM-16). 나눠 두어도 service 에는 저장소 하나만 주입된다.
 */
public interface CommentRepository
    extends JpaRepository<Comment, Long>, CommentQueryRepository, MyCommentQueryRepository {

  /**
   * 그 모집글에 이 사람이 살아 있는 댓글을 썼는가 (CH-02).
   *
   * <p><b>{@code ACTIVE} 만 센다.</b> 명세가 초대 진입점을 <i>"방장이 모집글 댓글에서 초대를 누른다"</i> 로 두었고 (CM-01 도 같은 줄이다)
   * 지워지거나 가려진 댓글은 목록에서 본문 없이 자리표시자로만 남거나 아예 빠진다 (CM-11) — 누를 자리가 없는 곳에서 온 초대는 화면에 없는 경로다.
   *
   * <p>대댓글도 댓글이라 함께 센다. 「댓글을 쓴 사람」에 답글만 단 사람을 빼는 줄이 명세에 없다.
   */
  boolean existsByPostIdAndAuthorIdAndStatus(Long postId, Long authorId, CommentStatus status);
}
