package com.duckmoim.companion.service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 모집글 수정 요청을 유스케이스의 어휘로 옮긴 것 (PO-06).
 *
 * <p>작성 커맨드와 담는 값이 거의 같은데 <b>둘로 나눈 이유는 요청자의 이름이다.</b> 작성에서는 요청자가 곧 방장이라 {@code hostId} 지만, 수정에서는
 * 요청자가 방장인지가 <i>판정 대상</i>이라 {@code requesterId} 다. 한 이름으로 합치면 아직 확인하지 않은 것을 확인된 것처럼 부르게 된다.
 *
 * <p><b>전량 치환이다.</b> 안 보낸 필드를 「그대로 두라」로 읽지 않는다 — 근거는 {@code CompanionPost.editByHost} 에 있다.
 *
 * @param eventExternalId 고른 행사의 <b>외부 식별자</b>다 (`pg_8417`). 숫자 PK 가 아니다 (API-설계.md 「2-3. 행사
 *     (Catalog)」). 없으면 행사를 뗀 것이고 만남시각을 검증하지 않는다 (PO-02)
 * @param meetAt 클라이언트가 보낸 오프셋 포함 시각. KST 판정과 UTC 저장은 도메인이 한다
 * @param capacity 선택 입력이다. 없으면 정원을 지운 것이다 (PO-05)
 */
public record CompanionPostEditCommand(
    Long postId,
    Long requesterId,
    String title,
    String content,
    String eventExternalId,
    OffsetDateTime meetAt,
    String meetPlace,
    BigDecimal meetLat,
    BigDecimal meetLng,
    Integer capacity) {}
