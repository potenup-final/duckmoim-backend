package com.duckmoim.safety.infra;

import com.duckmoim.safety.domain.Report;
import com.duckmoim.safety.domain.ReportTargetType;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 신고 저장소.
 *
 * <p>백오피스 목록(AD-02)은 필터가 선택이라 파생 쿼리 메서드로 감당할 수 없고, 대상 표시명을 세 곳에서 조인해야 한다. 커스텀 프래그먼트 {@link
 * ReportQueryRepository} 로 빼고 여기서 함께 상속한다 — service 에는 여전히 저장소 하나만 주입된다.
 */
public interface ReportRepository extends JpaRepository<Report, Long>, ReportQueryRepository {

  /**
   * 신고 행을 잠그고 읽는다 ({@code SELECT ... FOR UPDATE}).
   *
   * <p><b>{@code PROCESSING} 이 하는 일을 지키는 장치다.</b> 도메인-모델링.md 「6. 라이프사이클」이 그 상태를 둔 이유를 <i>"관리자가 넷이고
   * 백오피스가 창구 하나뿐이라 「처리 중」이 없으면 같은 건을 동시에 붙잡는다"</i> 고 적었다. 잠그지 않으면 그 문장이 코드에서 성립하지 않는다.
   *
   * <p><b>{@code findById} 로는 안 된다.</b> 잠기지 않은 읽기라 두 트랜잭션이 같이 지나간다 — 관리자 둘이 같은 신고를 동시에 잡으면 <b>둘 다
   * {@code PENDING} 을 보고 둘 다 전이표를 통과해 둘 다 200</b> 을 받고, {@code handledBy} 는 나중에 커밋한 쪽으로 덮어써진다.
   * {@code UserRepository#findByIdForUpdate} 가 토큰 경로에 대해 같은 판단을 했다.
   *
   * <p>잠근 뒤에는 늦게 온 쪽이 {@code PROCESSING} 을 읽고 전이표에서 걸려 {@code REPORT_TRANSITION_NOT_ALLOWED} 409 를
   * 받는다 — 판정은 그대로 도메인에 있고, 락은 <b>도메인이 최신 상태를 보게</b> 만들 뿐이다.
   *
   * <p><b>낙관적 락({@code @Version})을 쓰지 않았다.</b> 컬럼이 하나 늘고, Comment / Safety 대역에 남은 자리가 {@code V39}
   * 하나뿐이다. 한 신고를 동시에 처리하는 일이 드물어 직렬화 비용도 없다.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select report from Report report where report.id = :reportId")
  Optional<Report> findByIdForUpdate(@Param("reportId") Long reportId);

  /**
   * 이미 신고했는지 본다 (SF-01).
   *
   * <p><b>이것만으로는 부족하다.</b> 동시 요청 2건은 둘 다 이 조회를 통과한다. 실제 차단은 V34 의 유니크 제약이 하고, 이 조회는 흔한 경우에 409 를
   * <b>제약 위반 없이</b> 내주는 빠른 길이다 — 도메인 3.3 이 I-01 에 대해 정한 「사전 조회 + 이중 방어」와 같은 모양이다.
   */
  boolean existsByReporterIdAndTargetTypeAndTargetId(
      Long reporterId, ReportTargetType targetType, Long targetId);
}
