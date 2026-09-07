package com.duckmoim.architecture.fixture.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * 위반 픽스처 — service 의 public 시그니처에 Spring Data 타입이 나온다.
 *
 * <p>{@code findAll} 은 반환 타입으로, {@code count} 는 파라미터로 샌다. 둘 다 잡혀야 한다.
 *
 * <p>{@code countInternally} 는 <b>잡히면 안 된다.</b> 메서드 안에서 쓰는 것은 허용이다 — 저장소가 Spring Data 를 상속하므로 이것까지
 * 막으면 조회를 할 수 없다.
 */
public class LeakingPageService {

  public Page<String> findAll(String keyword) {
    return null;
  }

  public long count(Pageable pageable) {
    return 0L;
  }

  public long countInternally() {
    Pageable pageable = Pageable.unpaged();
    return pageable.getPageSize();
  }
}
