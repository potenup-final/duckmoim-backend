package com.duckmoim.companion.infra;

import com.duckmoim.companion.domain.CommentListQuery;
import java.util.List;

/**
 * 댓글 목록의 조회 (CM-06 · CM-07).
 *
 * <p><b>조회가 둘로 나뉜다.</b> 커서가 루트 댓글만 세기 때문이다 (API-설계.md 「3. 커서 정의」) — 대댓글까지 세면 페이지 경계에서 부모와 자식이 갈라진다.
 * 한 쿼리로 합치면 루트 20개를 세는 동안 대댓글이 자리를 차지한다.
 *
 * <p>커서 조건이 선택이라 파생 쿼리 메서드로는 감당할 수 없다. 커스텀 프래그먼트로 빼고 {@link CommentRepository} 가 함께 상속한다 — service
 * 에는 여전히 저장소 하나만 주입된다 ({@code EventQueryRepository} 와 같은 방식).
 */
public interface CommentQueryRepository {

  /**
   * 루트 댓글을 {@code (createdAt, id)} 오름차순으로 {@code size + 1} 건까지 읽는다.
   *
   * <p>한 건을 더 읽는 것은 다음 페이지 유무를 알기 위해서다. 별도 count 쿼리를 돌리지 않는다 — 목록 응답에 총 건수가 필요하지 않다 (API 컨벤션은 items
   * · nextCursor · hasNext 셋만 쓴다).
   */
  List<AuthoredComment> findRootSlice(CommentListQuery query);

  /**
   * 주어진 루트 댓글들의 대댓글을 <b>전부</b> 읽는다. 대댓글에는 페이지 개념이 없다 (CM-07).
   *
   * <p>{@code postId} 를 함께 받는 이유는 인덱스다. {@code (post_id, parent_id, created_at, id)} 의 선두 컬럼을 써야
   * 탐색과 정렬이 한 번에 끝난다. 대댓글은 부모와 같은 모집글에 속하므로 조건이 늘어도 결과가 달라지지 않는다.
   */
  List<AuthoredComment> findRepliesOf(Long postId, List<Long> parentIds);
}
