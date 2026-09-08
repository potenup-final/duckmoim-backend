package com.duckmoim.identity.service;

import org.springframework.stereotype.Component;

/**
 * 제재가 없다고만 답하는 임시 구현 (AU-12 이전).
 *
 * <p>{@code Sanction} 애그리게이트가 아직 없다. 응답에서 {@code sanction} 키를 빼는 방법도 있었으나, 그러면 클라이언트가 <b>키가 생기는 날
 * 코드를 고쳐야</b> 하고 API 설계 2-2 가 그 키를 이미 계약으로 적어 두었다.
 *
 * <p><b>Safety 담당이 실구현을 만들면 이 클래스를 지운다.</b> 남겨 두면 빈이 둘이 되어 주입이 실패하므로, 잊고 지나갈 수 없다 — 그것이 이 방식을 고른
 * 이유다.
 */
@Component
public class NoneSanctionReader implements SanctionReader {

  @Override
  public SanctionView read(Long userId) {
    return SanctionView.none();
  }
}
