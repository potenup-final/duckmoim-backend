package com.duckmoim.safety.presentation;

import com.duckmoim.safety.domain.ReportReason;
import com.duckmoim.safety.domain.ReportResult;
import com.duckmoim.safety.domain.ReportStatus;
import com.duckmoim.safety.domain.ReportTargetType;
import com.duckmoim.safety.service.ReportView;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 백오피스 신고 목록의 한 건 (화면-계약.md 「신고 목록」).
 *
 * <p>필드 이름은 계약을 그대로 따랐다.
 *
 * <p><b>{@code memo} 는 계약에 없는 필드다.</b> 화면 계약의 {@code result} 가 사람이 적는 문자열이었는데, STAR-78 이 세는 축
 * ({@code result})과 문장({@code memo})으로 나눴다 (도메인 5장이 미결로 남긴 자리). 문구 자체는 이 필드로 살아남는다.
 *
 * @param subject 신고당한 쪽의 표시명. {@code COMMENT} 는 <b>댓글 작성자 닉네임</b>이다 — 댓글은 제목이 없고 본문은 목록에 실을 수 없다.
 *     대상 행이 없어지면 null 이고 그래도 신고는 목록에 남는다 (STAR-60)
 * @param reporter 신고자 닉네임. 회원번호는 응답에 싣지 않는다
 * @param secret 대상이 비밀 댓글인가. 이 값이 true 면 화면이 「본문 보기」를 그리고, 그때 CM-17 경로를 불러 감사 로그가 남는다
 * @param createdAt 저장은 UTC 이고 응답은 KST 오프셋을 포함한다 (API-컨벤션.md 「필드 표기 규칙」)
 */
public record AdminReportItemResponse(
    Long id,
    ReportTargetType targetType,
    Long targetId,
    String subject,
    ReportReason reason,
    String detail,
    String reporter,
    OffsetDateTime createdAt,
    ReportStatus status,
    ReportResult result,
    String memo,
    boolean secret) {

  private static final ZoneId KST = ZoneId.of("Asia/Seoul");

  static AdminReportItemResponse from(ReportView view) {
    return new AdminReportItemResponse(
        view.id(),
        view.targetType(),
        view.targetId(),
        view.subject(),
        view.reason(),
        view.detail(),
        view.reporter(),
        toKst(view.createdAt()),
        view.status(),
        view.result(),
        view.memo(),
        view.secret());
  }

  private static OffsetDateTime toKst(LocalDateTime storedInUtc) {
    return storedInUtc.atOffset(ZoneOffset.UTC).atZoneSameInstant(KST).toOffsetDateTime();
  }
}
