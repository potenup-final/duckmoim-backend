package com.duckmoim.companion.infra;

import com.duckmoim.companion.domain.CommentListQuery;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

  /**
   * 댓글 한 건을 작성자와 함께 읽는다 (CM-17).
   *
   * <p><b>{@code status} 를 보지 않는다.</b> 소프트 삭제·블라인드된 댓글도 그대로 돌려준다. 지운 댓글을 404 로 만들면 신고당한 사람이 댓글을 지우는
   * 것으로 판정을 막을 수 있어서다 — API-설계.md 「2-5. 댓글 (Companion)」이 <i>"본문은 소프트 삭제라 남아 있어 백오피스가 CM-17 로 판단할
   * 재료가 된다"</i> 고 정했다. <b>일반 조회 경로의 404 는 그대로다</b> (API-컨벤션.md 「Status Code 규칙」).
   *
   * <p>{@code findById} 가 아닌 이유는 응답에 작성자 닉네임과 아바타가 필요하기 때문이다. 목록과 같은 조인을 쓴다.
   */
  Optional<AuthoredComment> findAuthoredById(Long commentId);

  /**
   * 모집글별 댓글 수를 센다 (CM-12 · I-11).
   *
   * <p><b>저장하지 않고 조회 때 센다.</b> I-11 의 검증 위치가 「조회 시 집계」이고 이중 방어가 없다 — 저장하지 않으므로 어긋날 저장값이 없다.
   *
   * <p>세는 범위는 <b>비밀 포함 · 삭제·블라인드 제외 · 대댓글 포함</b>이다 (CM-12). 비밀 댓글이 세어지는 것은 존재 자체는 가리지 않기 때문이고
   * (도메인-모델링.md 「7.1 가시성과 권한」이 가리는 것을 본문으로 한정했다), 자리표시자가 빠지는 것은 CM-12 가 그렇게 정했다.
   *
   * <p><b>목록 한 페이지를 한 쿼리로 센다.</b> 모집글마다 부르면 20건이면 쿼리가 20개다. 값이 없는 모집글은 결과에 아예 없으므로 호출부가 0 으로 읽는다 —
   * {@code GROUP BY} 는 행이 없는 그룹을 만들지 않는다.
   *
   * @return 모집글 id → 댓글 수. 댓글이 없는 모집글은 키가 없다
   */
  Map<Long, Long> countActiveByPostIds(List<Long> postIds);
}
