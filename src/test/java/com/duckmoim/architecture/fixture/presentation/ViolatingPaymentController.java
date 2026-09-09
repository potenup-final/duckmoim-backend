package com.duckmoim.architecture.fixture.presentation;

import com.duckmoim.architecture.fixture.infra.PaymentJpaRepository;

/**
 * 위반 — presentation 이 service 를 건너뛰고 infra 저장소를 직접 참조한다.
 *
 * <p>infra 에 닿을 수 있는 것은 service 뿐이다. 이 방향을 열어두면 트랜잭션 경계가 controller 로 새고, 유스케이스가 어디에 있는지 알 수 없게 된다.
 */
public class ViolatingPaymentController {

  private final PaymentJpaRepository repository = new PaymentJpaRepository();

  public String pay() {
    return repository.findRow();
  }
}
