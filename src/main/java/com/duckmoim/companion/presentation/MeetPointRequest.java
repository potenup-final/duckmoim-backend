package com.duckmoim.companion.presentation;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * 만남 지점 요청 (PO-03 · I-05).
 *
 * <p><b>셋 다 필수다.</b> 화면-계약.md 「모집글 (PO)」이 <i>"핀 없이 못 올린다"</i> 고 정했다. 좌표가 빠진 요청이 여기서 400 으로 끝나고
 * 도메인까지 가지 않는다 (API-컨벤션.md 「Validation 규칙」).
 *
 * <p>범위 검증은 DB 체크 제약과 같은 숫자다 — {@code ck_companion_post_lat} · {@code ck_companion_post_lng}. 지구 밖
 * 좌표를 받아 저장에서 500 이 나가지 않게 한다.
 */
public record MeetPointRequest(
    @Schema(description = "사람이 적은 장소명", example = "더현대 서울 지하 1층 팝업 아이코닉")
        @NotBlank(message = "만남 장소를 입력해 주세요.")
        @Size(max = 100, message = "만남 장소는 100자 이하여야 합니다.")
        String place,
    @Schema(description = "지도에서 찍은 위도", example = "37.5256381")
        @NotNull(message = "지도에서 만남 지점을 찍어 주세요.")
        @DecimalMin(value = "-90", message = "위도가 올바르지 않습니다.")
        @DecimalMax(value = "90", message = "위도가 올바르지 않습니다.")
        BigDecimal lat,
    @Schema(description = "지도에서 찍은 경도", example = "126.9289384")
        @NotNull(message = "지도에서 만남 지점을 찍어 주세요.")
        @DecimalMin(value = "-180", message = "경도가 올바르지 않습니다.")
        @DecimalMax(value = "180", message = "경도가 올바르지 않습니다.")
        BigDecimal lng) {}
