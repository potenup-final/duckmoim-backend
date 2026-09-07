package com.duckmoim.architecture.fixture.infra;

import org.springframework.transaction.annotation.Transactional;

/**
 * 위반 픽스처 — infra 에 트랜잭션 경계를 둔다.
 *
 * <p>저장소 호출마다 경계가 생겨 service 의 유스케이스 하나가 원자적이지 않게 된다.
 */
public class TransactionalRepository {

  @Transactional
  public void save(String value) {
    // 아무것도 하지 않는다. 애노테이션의 위치만이 검사 대상이다.
  }
}
