package com.duckmoim.admin.infra;

import com.duckmoim.admin.domain.AuditLogListQuery;
import java.util.List;

/**
 * 감사 로그 목록의 조회 (AD-05).
 *
 * <p>행위자 닉네임을 얻는 조인이 필요해 파생 쿼리 메서드로는 감당할 수 없다. 커스텀 프래그먼트로 두고 {@link AuditLogRepository} 가 함께 상속한다 —
 * service 에는 여전히 저장소 하나만 주입된다.
 */
public interface AuditLogQueryRepository {

  /**
   * 감사 로그를 {@code (at, id)} <b>내림차순</b>으로 {@code size + 1} 건까지 읽는다.
   *
   * <p>한 건을 더 읽는 것은 다음 페이지 유무를 알기 위해서다. 별도 count 쿼리를 돌리지 않는다 — 커서 페이지네이션 응답에 총 건수가 없다 (API-컨벤션.md
   * 「공통 응답 형식」).
   */
  List<ActedAuditLog> findSlice(AuditLogListQuery query);
}
