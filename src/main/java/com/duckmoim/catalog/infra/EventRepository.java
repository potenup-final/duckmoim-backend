package com.duckmoim.catalog.infra;

import com.duckmoim.catalog.domain.Event;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

  /**
   * 요청에 실린 외부 식별자들로 이미 있는 행사를 한 번에 읽는다 (EV-03).
   *
   * <p>200여 건을 건마다 {@code findByExternalId} 로 조회하면 쿼리가 그만큼 나간다. 신규와 갱신을 가르는 데 필요한 것은 「이 식별자가 이미
   * 있는가」뿐이라 한 번에 읽어 맵으로 쓴다.
   */
  List<Event> findByExternalIdIn(Collection<String> externalIds);

  /**
   * 목록에 나갈 수 있는 행사 수 (EV-04).
   *
   * <p>적재 응답의 {@code total} 이다. {@code ends_on} 필터를 걸지 않는다 — 필터를 끼우면 자정을 넘긴 실행에서 기준이 흔들려, 건수가 안 맞을
   * 때 적재가 샌 건지 날이 바뀐 건지 구분할 수 없다.
   *
   * <p>이 값은 <b>{@code events.json} 건수와 같아지지 않는다.</b> 사라진 행사를 숨기고 지우지 않으므로 단조 증가한다 (D-7). 두 번 돌려 이
   * 값이 늘지 않는 것이 EV-03 의 멱등성 판정이다.
   */
  @Override
  long count();

  /**
   * 기간이 남았는데 오래 안 잡힌 행사 수 (D-7).
   *
   * <p>적재 응답의 {@code hidden} 이다. <b>0 이 계속 나오면 유령이 안 걷히고 있다는 뜻이고, 갑자기 커지면 수집이 절반만 된 것이다.</b> 러너는 DB
   * 를 못 보므로(D-8) 이 값이 숨김 동작을 확인할 유일한 창이다.
   *
   * <p>기간이 지난 행사는 세지 않는다. 그것은 이미 {@code ends_on} 필터가 거르고 있어 숨김의 효과가 없다.
   */
  @Query("select count(e) from Event e where e.endsOn >= :today and e.lastCrawledAt < :staleBefore")
  long countStale(@Param("today") LocalDate today, @Param("staleBefore") Instant staleBefore);
}
