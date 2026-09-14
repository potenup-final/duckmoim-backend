package com.duckmoim.safety.infra;

import com.duckmoim.safety.domain.SanctionListQuery;
import java.util.List;

/**
 * 백오피스 제재 목록의 조회 (AD-10).
 *
 * <p>필터가 선택이고 커서의 정렬 축이 둘이라 파생 쿼리 메서드로는 감당할 수 없다. 커스텀 프래그먼트로 빼고 {@link SanctionRepository} 가 함께
 * 상속한다 — service 에는 여전히 저장소 하나만 주입된다 ({@code ReportQueryRepository} 와 같은 방식).
 */
public interface SanctionQueryRepository {

  /**
   * 지금 유효한 제재를 만료 임박순으로 {@code size + 1} 건까지 읽는다.
   *
   * <p>한 건을 더 읽는 것은 다음 페이지 유무를 알기 위해서다. 별도 count 쿼리를 돌리지 않는다 — 목록 응답에 총 건수가 필요하지 않다 (API 컨벤션은 items
   * · nextCursor · hasNext 셋만 쓴다).
   *
   * <p><b>여기서만 활성 판정이 SQL 이다.</b> 회원 하나를 읽는 경로({@link SanctionRepository})는 「푼 적 없는 제재」까지만 거르고 만료는
   * 도메인이 판정한다. 이 목록은 좁힐 {@code userId} 가 없어 그럴 수 없다 — 메모리에서 거르면 한 페이지가 요청한 크기보다 작아져 다음 커서가 어긋난다. 대신
   * SQL 이 보는 것은 <b>저장된 만료 시각</b>이라 규칙 자체는 여전히 도메인에 한 벌뿐이다 ({@code Sanction#expiryOf}).
   */
  List<SanctionedUser> findSlice(SanctionListQuery query);
}
