package com.duckmoim.safety.presentation;

import com.duckmoim.safety.domain.SanctionKind;
import com.duckmoim.safety.service.SanctionListView;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 백오피스 제재 목록의 한 건 (화면-계약.md 「제재 목록 {@code GET /api/v1/admin/sanctions} — AD-10」).
 *
 * <p>필드 이름은 계약을 그대로 따랐다.
 *
 * <p><b>{@code id} 가 아니라 {@code sanctionId} 다.</b> 해제 경로({@code DELETE
 * /api/v1/admin/users/{userId}/sanctions/{sanctionId}})에 그대로 넣는 값이라, 제재 생성 응답이 같은 판단을 했다.
 *
 * <p><b>{@code until} 과 {@code expiresAt} 이 둘 다 실린다.</b> 같아 보이지만 다른 값이다 — 앞은 관리자가 입력한 값이라 {@code
 * SUSPENDED} 에만 있고, 뒤는 서버가 계산한 해소 시각이라 {@code WARNED} 에도 있다.
 *
 * @param nickname 제재받은 회원의 닉네임. 카카오 회원번호는 응답에 싣지 않는다
 * @param reason 본인에게 보여주는 정보라 노출해도 된다 (AD-04)
 * @param issuedAt 저장은 UTC 이고 응답은 KST 오프셋을 포함한다 (API-컨벤션.md 「필드 표기 규칙」)
 */
public record AdminSanctionItemResponse(
    Long sanctionId,
    Long userId,
    String nickname,
    SanctionKind kind,
    String reason,
    OffsetDateTime issuedAt,
    OffsetDateTime until,
    OffsetDateTime expiresAt) {

  private static final ZoneId KST = ZoneId.of("Asia/Seoul");

  static AdminSanctionItemResponse from(SanctionListView view) {
    return new AdminSanctionItemResponse(
        view.sanctionId(),
        view.userId(),
        view.nickname(),
        view.kind(),
        view.reason(),
        toKst(view.issuedAt()),
        toKst(view.until()),
        toKst(view.expiresAt()));
  }

  /** 스스로 풀리지 않는 제재는 만료가 비어 있다. 그대로 null 로 내린다 — 화면이 「기한 없음」을 그린다. */
  private static OffsetDateTime toKst(LocalDateTime storedInUtc) {
    if (storedInUtc == null) {
      return null;
    }

    return storedInUtc.atOffset(ZoneOffset.UTC).atZoneSameInstant(KST).toOffsetDateTime();
  }
}
