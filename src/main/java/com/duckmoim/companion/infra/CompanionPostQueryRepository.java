package com.duckmoim.companion.infra;

import com.duckmoim.companion.domain.PostListQuery;
import java.util.List;
import java.util.Optional;

/**
 * 모집글 조회 (PO-08 · PO-11).
 *
 * <p><b>목록과 상세가 한 인터페이스에 있다.</b> 둘이 읽는 것이 같기 때문이다 — 모집글에 방장과 행사 외부 식별자를 붙인 모양 ({@link
 * AuthoredPost}) 이고, 갈리는 것은 응답에 본문 전체를 싣는지 잘라 싣는지뿐이다 (API-설계.md 「2-4. 모집글 (Companion)」). 조인 조건이 두
 * 곳에 복사되면 한쪽만 고치는 날이 온다.
 *
 * <p>{@code status} 와 커서가 모두 선택이라 파생 쿼리 메서드로는 감당할 수 없다. 커스텀 프래그먼트로 빼고 {@link
 * CompanionPostRepository} 가 함께 상속한다 — service 에는 여전히 저장소 하나만 주입된다 ({@code CommentQueryRepository}
 * 와 같은 방식).
 */
public interface CompanionPostQueryRepository {

  /**
   * 모집글을 {@code (meetAt, id)} 오름차순으로 {@code size + 1} 건까지 읽는다 (PO-08).
   *
   * <p>한 건을 더 읽는 것은 다음 페이지 유무를 알기 위해서다. 별도 count 쿼리를 돌리지 않는다 — 목록 응답에 총 건수가 필요하지 않다 (API 컨벤션은 items
   * · nextCursor · hasNext 셋만 쓴다).
   */
  List<AuthoredPost> findSlice(PostListQuery query);

  /**
   * 모집글 한 건을 방장과 함께 읽는다 (PO-11).
   *
   * <p>없으면 비어 있다. {@code POST_NOT_FOUND} 로 옮기는 것은 service 의 일이다 — 저장소가 도메인 에러 코드를 던지면 조회 경로마다 판정이
   * 흩어진다.
   */
  Optional<AuthoredPost> findAuthored(Long postId);
}
