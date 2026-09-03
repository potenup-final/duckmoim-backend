package com.duckmoim.architecture.fixture.service;

import com.duckmoim.architecture.fixture.infra.PaymentJpaRepository;

/** 위반 — service 가 infra 구현을 직접 참조한다 (아키텍처 컨벤션 규칙 4). */
public class ViolatingPaymentService {

  private final PaymentJpaRepository repository = new PaymentJpaRepository();

  public String pay() {
    return repository.findRow();
  }
}
