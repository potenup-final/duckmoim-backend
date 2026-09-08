package com.duckmoim.companion.infra;

import com.duckmoim.companion.domain.MyCommentListQuery;
import java.util.List;

/**
 * 내 댓글 내역의 조회 (CM-16).
 *
 * <p><b>조회가 하나다.</b> 목록(CM-06 · CM-07)이 루트와 대댓글로 갈리는 것은 커서가 루트만 세기 때문인데, 내 내역의 커서는 내가 쓴 댓글을 세므로 루트와
 * 대댓글을 가를 이유가 없다 — 내가 쓴 대댓글도 내 내역에 있어야 한다.
 *
 * <p>커서 조건이 선택이라 파생 쿼리 메서드로는 감당할 수 없다. {@link CommentQueryRepository} 와 나란히 커스텀 프래그먼트로 두고 {@link
 * CommentRepository} 가 함께 상속한다 — service 에는 여전히 저장소 하나만 주입된다.
 */
public interface MyCommentQueryRepository {

  /**
   * 내가 쓴 살아 있는 댓글을 {@code (createdAt, id)} <b>내림차순</b>으로 {@code size + 1} 건까지 읽는다.
   *
   * <p>한 건을 더 읽는 것은 다음 페이지 유무를 알기 위해서다. 별도 count 쿼리를 돌리지 않는다 — 커서 페이지네이션 응답에 총 건수가 없다 (API-컨벤션.md
   * 「공통 응답 형식」).
   */
  List<MyComment> findMySlice(MyCommentListQuery query);
}
