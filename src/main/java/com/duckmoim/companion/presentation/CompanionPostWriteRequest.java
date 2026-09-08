package com.duckmoim.companion.presentation;

import com.duckmoim.companion.service.CompanionPostWriteCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;

/**
 * 모집글 작성 요청 (화면-계약.md 「작성 `POST /api/v1/posts` — PO-01」).
 *
 * <p><b>필수는 셋이다</b> — 제목 · 만남시각 · 만남지점. 행사 · 본문 · 정원은 선택이다 (PO-01).
 *
 * <p>길이와 범위를 여기서만 본다. API-컨벤션.md 「Validation 규칙」이 단순 형식 검증을 Bean Validation 으로 정했고, 도메인과 DB 에 같은
 * 숫자를 겹쳐 두면 한 곳만 고치는 날이 온다. {@code Comment} 가 본문 길이를 두고 내린 판단과 같다.
 *
 * <p><b>정원 범위만 여기서 보지 않는다.</b> 정본 표가 {@code POST_CAPACITY_OUT_OF_RANGE} 를 I-03 근거의 별도 코드로 못박았는데,
 * 여기서 {@code INVALID_INPUT} 으로 먼저 걸러 버리면 그 코드가 어느 경로로도 안 나간다. 판정은 {@code Capacity} 가 진다.
 *
 * @param eventId 행사의 <b>외부 식별자</b>다 (`pg_8417`). 숫자 PK 가 아니다
 */
public record CompanionPostWriteRequest(
    @Schema(description = "제목. 1~40자", example = "에이티즈 팝업 오픈런 같이 하실 분")
        @NotBlank(message = "제목을 입력해 주세요.")
        @Size(max = 40, message = "제목은 40자 이하여야 합니다.")
        String title,
    @Schema(description = "본문. 500자 이하", nullable = true, example = "혼자 가려니...")
        @Size(max = 500, message = "본문은 500자 이하여야 합니다.")
        String content,
    @Schema(description = "고른 행사의 외부 식별자. 없으면 만남시각을 검증하지 않는다", nullable = true, example = "pg_8417")
        String eventId,
    @Schema(
            description = "정원. 없으면 정원을 표시하지 않는다. 값이 있으면 2~6",
            nullable = true,
            minimum = "2",
            maximum = "6",
            example = "4")
        Integer capacity,
    @Schema(description = "만남시각. 오프셋을 포함한다", example = "2026-09-14T09:00:00+09:00")
        @NotNull(message = "만남시각을 입력해 주세요.")
        OffsetDateTime meetAt,
    @Schema(description = "만남 지점. 장소명과 좌표가 모두 필요하다")
        @NotNull(message = "지도에서 만남 지점을 찍어 주세요.")
        @Valid
        MeetPointRequest meetPoint) {

  CompanionPostWriteCommand toCommand(Long hostId) {
    return new CompanionPostWriteCommand(
        hostId,
        title,
        content,
        eventId,
        meetAt,
        meetPoint.place(),
        meetPoint.lat(),
        meetPoint.lng(),
        capacity);
  }
}
