package com.duckmoim.companion.service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 모집글 작성 요청을 유스케이스의 어휘로 옮긴 것 (PO-01 · PO-02 · PO-03 · PO-05).
 *
 * <p>요청 DTO 를 그대로 받지 않는다 (아키텍처-컨벤션.md 「service」). {@code hostId} 는 요청 본문이 아니라 인증에서 나온다 — 남의 이름으로 쓰는
 * 것을 막는 유일한 장치다.
 *
 * @param eventExternalId 고른 행사의 <b>외부 식별자</b>다 (`pg_8417`). 숫자 PK 가 아니다 (API-설계.md 「2-3. 행사
 *     (Catalog)」). 없으면 행사를 고르지 않은 것이고 만남시각을 검증하지 않는다 (PO-02)
 * @param meetAt 클라이언트가 보낸 오프셋 포함 시각. KST 판정과 UTC 저장은 도메인이 한다
 * @param capacity 선택 입력이다. 없으면 정원을 표시하지 않는다 (PO-05)
 */
public record CompanionPostWriteCommand(
    Long hostId,
    String title,
    String content,
    String eventExternalId,
    OffsetDateTime meetAt,
    String meetPlace,
    BigDecimal meetLat,
    BigDecimal meetLng,
    Integer capacity) {

  public boolean hasEvent() {
    return eventExternalId != null && !eventExternalId.isBlank();
  }
}
