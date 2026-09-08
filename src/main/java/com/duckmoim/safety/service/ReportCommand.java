package com.duckmoim.safety.service;

import com.duckmoim.safety.domain.ReportReason;
import com.duckmoim.safety.domain.ReportTargetType;

/**
 * 신고 접수 요청을 유스케이스의 어휘로 옮긴 것.
 *
 * @param reporterId 인증에서 나온다. 요청 본문으로 받지 않는 것이 남의 이름으로 신고하는 것을 막는 장치다
 * @param detail 선택 입력이다. 명세서가 「사유 카테고리 + 상세」 라고만 하고 필수 여부를 정하지 않았다
 */
public record ReportCommand(
    Long reporterId,
    ReportTargetType targetType,
    Long targetId,
    ReportReason reason,
    String detail) {}
