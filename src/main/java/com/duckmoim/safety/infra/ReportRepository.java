package com.duckmoim.safety.infra;

import com.duckmoim.safety.domain.Report;
import com.duckmoim.safety.domain.ReportTargetType;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 신고 저장소.
 *
 * <p>백오피스 목록(AD-02)은 필터가 선택이라 파생 쿼리 메서드로 감당할 수 없고, 대상 표시명을 세 곳에서 조인해야 한다. 커스텀 프래그먼트 {@link
 * ReportQueryRepository} 로 빼고 여기서 함께 상속한다 — service 에는 여전히 저장소 하나만 주입된다.
 */
public interface ReportRepository extends JpaRepository<Report, Long>, ReportQueryRepository {

  /**
   * 이미 신고했는지 본다 (SF-01).
   *
   * <p><b>이것만으로는 부족하다.</b> 동시 요청 2건은 둘 다 이 조회를 통과한다. 실제 차단은 V34 의 유니크 제약이 하고, 이 조회는 흔한 경우에 409 를
   * <b>제약 위반 없이</b> 내주는 빠른 길이다 — 도메인 3.3 이 I-01 에 대해 정한 「사전 조회 + 이중 방어」와 같은 모양이다.
   */
  boolean existsByReporterIdAndTargetTypeAndTargetId(
      Long reporterId, ReportTargetType targetType, Long targetId);
}
