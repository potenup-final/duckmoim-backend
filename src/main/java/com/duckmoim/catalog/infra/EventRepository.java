package com.duckmoim.catalog.infra;

import com.duckmoim.catalog.domain.Event;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 행사 저장소.
 *
 * <p>{@link EventQueryRepository} 를 함께 상속해 동적 조회까지 이 인터페이스 하나로 노출한다. service 가 저장소 둘을 주입받지 않게 하려는
 * 것이다 (아키텍처 컨벤션 · 저장소).
 */
public interface EventRepository extends JpaRepository<Event, Long>, EventQueryRepository {

  /**
   * 외부 식별자로 한 건을 읽는다 (EV-07).
   *
   * <p>숫자 PK 가 아니라 외부 식별자로 찾는다. 상세의 주소가 {@code /e/pg_8709} 라 들어오는 값이 그것이고, 도메인 4장도 「URL 은 외부 식별자로
   * 관리한다」 다 (API 설계 2-3). 유니크 제약이 걸려 있어 한 건을 넘지 않는다 (I-12).
   *
   * <p><b>끝난 행사도 준다.</b> {@code endsOn} 조건을 걸지 않는다 — 끝난 행사를 거르는 것은 목록의 성질이고, 이 엔드포인트는 애초에 목록에 없는
   * 행사로 직접 들어오는 경로를 위해 있다 (API 설계 2-3).
   */
  Optional<Event> findByExternalId(String externalId);
}
