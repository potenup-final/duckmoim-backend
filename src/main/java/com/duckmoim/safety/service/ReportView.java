package com.duckmoim.safety.service;

import com.duckmoim.safety.domain.ReportReason;
import com.duckmoim.safety.domain.ReportResult;
import com.duckmoim.safety.domain.ReportStatus;
import com.duckmoim.safety.domain.ReportTargetType;
import java.time.LocalDateTime;

/**
 * 백오피스 목록에 실리는 신고 한 건 (AD-02).
 *
 * <p>엔티티를 service 의 public 시그니처에 노출하지 않는다는 규칙(아키텍처-컨벤션.md 「service」)에 따라 둔 결과 객체다. 값 객체와 enum 은
 * presentation 이 참조해도 된다 (같은 문서 「의존성 방향」 3번).
 *
 * @param subject 신고당한 쪽의 표시명. 대상 행이 없어지면 null 이고, 그래도 신고는 목록에 남는다 (STAR-60)
 * @param reporter 신고자 닉네임. 회원번호는 응답에 싣지 않는다
 * @param secret 대상이 비밀 댓글인가. 댓글이 아니면 false 다 — 화면이 이 값으로 「본문 보기」 버튼을 그린다 (CM-17)
 * @param createdAt 저장된 값 그대로 UTC 다. KST 로 옮기는 것은 presentation 이 한다
 */
public record ReportView(
    Long id,
    ReportTargetType targetType,
    Long targetId,
    String subject,
    ReportReason reason,
    String detail,
    String reporter,
    LocalDateTime createdAt,
    ReportStatus status,
    ReportResult result,
    String memo,
    boolean secret) {}
