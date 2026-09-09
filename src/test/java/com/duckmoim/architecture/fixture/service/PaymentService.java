package com.duckmoim.architecture.fixture.service;

import com.duckmoim.architecture.fixture.infra.PaymentJpaRepository;

/**
 * 위반이 아니다 — service 가 infra 저장소를 직접 주입받는 것은 아키텍처 컨벤션 규칙 3 이 허용한 방향이다.
 *
 * <p>여기 남겨두는 이유는 <b>규칙이 과하게 잡지 않는지</b>도 함께 보기 위해서다. 이 클래스가 위반으로 잡히면 프로덕션의 모든 조회 service 가 같이 막힌다.
 */
public class PaymentService {

  private final PaymentJpaRepository repository = new PaymentJpaRepository();

  public String pay() {
    return repository.findRow();
  }
}
