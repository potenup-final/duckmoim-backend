package com.duckmoim.safety.service;

import com.duckmoim.safety.domain.ReportResult;
import com.duckmoim.safety.domain.ReportStatus;

/**
 * 신고 한 건을 처리하라는 요청 (AD-03).
 *
 * <p>요청 DTO 를 service 로 그대로 내리지 않는다 (아키텍처-컨벤션.md 「의존성 방향」). {@code CommentWriteCommand} 와 같은 자리다.
 *
 * @param adminUserId 처리한 관리자의 회원번호. <b>인가가 아니라 이력이다</b> — 관리자 판정은 관문에서 이미 끝났고, 이 값은 「누가 처리했는가」로
 *     {@code Report} 에 남는다 (도메인 1.1 각주)
 * @param result 종결 사유. {@code RESOLVED} 로 갈 때만 쓰인다
 * @param memo 관리자가 남기는 판단 맥락. 없어도 된다
 */
public record ReportHandleCommand(
    Long reportId, ReportStatus status, ReportResult result, String memo, Long adminUserId) {}
