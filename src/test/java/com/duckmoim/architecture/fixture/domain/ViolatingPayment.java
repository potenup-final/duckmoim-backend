package com.duckmoim.architecture.fixture.domain;

import com.duckmoim.architecture.fixture.service.ViolatingPaymentService;
import org.springframework.stereotype.Component;

/** 위반 두 개 — domain 이 상위 레이어(service)를 참조하고, Spring 애너테이션에 묶여 있다. */
@Component
public class ViolatingPayment {

  private final ViolatingPaymentService service = new ViolatingPaymentService();

  public String settle() {
    return service.pay();
  }
}
