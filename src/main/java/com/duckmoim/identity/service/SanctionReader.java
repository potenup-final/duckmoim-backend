package com.duckmoim.identity.service;

/**
 * 그 회원의 제재 상태를 읽는다 (AU-12).
 *
 * <p><b>포트만 두고 실구현은 Safety 가 꽂는다.</b> 포트가 없으면 AU-12 를 하는 쪽이 <b>내 조회 서비스를 고쳐야</b> 하고, 그 순간 {@code
 * identity} 안에 {@code safety} 의존이 생긴다. 화살표는 컨텍스트 맵대로 Safety → Identity 여야 한다.
 *
 * <p>한때 {@code Sanction} 이 없어 「제재 없음」을 고정으로 돌려주는 임시 구현이 여기 있었다. STAR-80 이 {@code
 * SanctionViewReader} 를 꽂으면서 지웠다 — 빈이 둘이 되면 주입이 실패하므로 잊고 지나갈 수 없는 방식이었다.
 */
public interface SanctionReader {

  SanctionView read(Long userId);
}
