package com.duckmoim.identity.service;

/**
 * 그 회원의 제재 상태를 읽는다 (AU-12).
 *
 * <p><b>포트만 두고 실구현은 Safety 가 꽂는다.</b> {@code Sanction} 이 아직 없어서 {@link NoneSanctionReader} 가 「제재
 * 없음」을 고정으로 돌려준다. 포트가 없으면 AU-12 를 하는 쪽이 <b>내 조회 서비스를 고쳐야</b> 하고, 그 순간 {@code identity} 안에 {@code
 * safety} 의존이 생긴다.
 *
 * <p>D 티켓이 I-14(제재 중 쓰기 차단)를 같은 모양으로 처리한다 — 포트 + no-op.
 */
public interface SanctionReader {

  SanctionView read(Long userId);
}
