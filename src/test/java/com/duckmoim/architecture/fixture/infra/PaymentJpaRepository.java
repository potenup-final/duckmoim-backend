package com.duckmoim.architecture.fixture.infra;

/** service 가 이것을 직접 참조하는 것이 위반이다. 이 클래스 자체는 정상이다. */
public class PaymentJpaRepository {
  public String findRow() {
    return "row";
  }
}
