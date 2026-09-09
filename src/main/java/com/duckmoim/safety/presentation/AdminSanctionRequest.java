package com.duckmoim.safety.presentation;

import com.duckmoim.safety.domain.SanctionKind;
import com.duckmoim.safety.service.SanctionCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * 제재 요청 (AD-04 · 화면-계약.md 「제재·해제」).
 *
 * <p><b>{@code reason} 이 필수다.</b> 화면 계약이 <i>"{@code reason} 은 본인에게 그대로 보이므로 필수다"</i> 라고 적었다. 비면 안내
 * 화면이 이유 없이 뜬다.
 *
 * <p><b>{@code kind} 는 {@code NONE} 을 뺀 넷이다.</b> 그 값이 {@link SanctionKind} 에 아예 없으므로 역직렬화가 먼저 걸러
 * {@code INVALID_INPUT} 400 이 된다.
 *
 * <p><b>{@code until} 은 {@code SUSPENDED} 일 때만 준다.</b> 어긋난 조합은 도메인이 400 으로 막는다 — 여기서 판정하면 같은 규칙이 두
 * 곳에 생긴다.
 */
public record AdminSanctionRequest(
    @Schema(description = "WARNED · AGE_HOLD · SUSPENDED · BANNED") @NotNull SanctionKind kind,
    @Schema(description = "본인에게 그대로 보인다") @NotBlank @Size(max = 500) String reason,
    @Schema(description = "기간 정지의 해제 시각. SUSPENDED 일 때만 준다", nullable = true)
        OffsetDateTime until) {

  /** 저장은 UTC 다 (도메인-모델링.md 4장). 오프셋을 달고 들어온 값을 그 시점의 UTC 로 옮긴다. */
  SanctionCommand toCommand(Long userId, Long adminUserId) {
    return new SanctionCommand(userId, kind, reason, toUtc(until), adminUserId);
  }

  private static LocalDateTime toUtc(OffsetDateTime given) {
    return given == null ? null : given.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
  }
}
