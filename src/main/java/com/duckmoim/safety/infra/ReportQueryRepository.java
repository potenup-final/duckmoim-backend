package com.duckmoim.safety.infra;

import com.duckmoim.safety.domain.ReportListQuery;
import java.util.List;

/**
 * 백오피스 신고 목록의 조회 (AD-02).
 *
 * <p>필터가 선택이라 파생 쿼리 메서드로는 감당할 수 없다. 커스텀 프래그먼트로 빼고 {@link ReportRepository} 가 함께 상속한다 — service 에는
 * 여전히 저장소 하나만 주입된다 ({@code CommentQueryRepository} 와 같은 방식).
 */
public interface ReportQueryRepository {

  /**
   * 신고를 최신순으로 {@code size + 1} 건까지 읽는다.
   *
   * <p>한 건을 더 읽는 것은 다음 페이지 유무를 알기 위해서다. 별도 count 쿼리를 돌리지 않는다 — 목록 응답에 총 건수가 필요하지 않다 (API 컨벤션은 items
   * · nextCursor · hasNext 셋만 쓴다).
   *
   * <p><b>대상이 없어진 신고도 읽는다.</b> 지운 댓글·블라인드된 댓글에 대한 접수를 목록에서 빼지 않는다 (STAR-60) — 조치가 댓글 삭제가 아니라 유저 제재로
   * 가므로 (I-14) 대상이 없어진 뒤에도 처리가 생산적이다.
   */
  List<ReportedTarget> findSlice(ReportListQuery query);
}
