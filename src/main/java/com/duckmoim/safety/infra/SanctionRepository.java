package com.duckmoim.safety.infra;

import com.duckmoim.safety.domain.Sanction;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 제재 저장소 (AD-04).
 *
 * <p><b>「푼 적 없는 제재」까지만 저장소가 거른다.</b> 만료는 저장값이 아니라 판정이라 (도메인 {@code Sanction#isActiveAt}) 쿼리 조건에 넣지
 * 않는다 — 넣으면 「지금」이 조건에 들어가 {@code idx_sanction_active} 를 못 타고, 같은 판정이 SQL 과 도메인 두 곳에 생긴다.
 *
 * <p>그래서 이 메서드는 <b>후보</b>를 준다. 그중 지금 유효한 것을 고르는 일은 service 가 도메인에 맡긴다.
 *
 * <p><b>여러 건을 돌려주는 이유.</b> 활성 제재는 한 유저에 최대 하나라는 것이 이 티켓의 판단이지만 (도메인 6장 상태 축이 {@code NONE} 에서만
 * 출발한다), 그것은 <b>거는 쪽이 지키는 규칙</b>이지 표가 보장하는 것이 아니다. 읽는 쪽이 하나를 가정하면 규칙이 깨진 날 조용히 한 건만 보고 지나간다.
 */
public interface SanctionRepository extends JpaRepository<Sanction, Long> {

  /**
   * 아직 풀리지 않은 제재를 최근 순으로 읽는다.
   *
   * <p>{@code idx_sanction_active (user_id, released_at)} 를 탄다.
   */
  List<Sanction> findByUserIdAndReleasedAtIsNullOrderByIssuedAtDesc(Long userId);
}
