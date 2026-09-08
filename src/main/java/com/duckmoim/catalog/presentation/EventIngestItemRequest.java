package com.duckmoim.catalog.presentation;

import com.duckmoim.catalog.domain.EventCrawl;
import com.duckmoim.catalog.domain.EventKind;
import com.duckmoim.catalog.domain.EventSource;
import com.duckmoim.catalog.domain.PlaceKind;
import com.duckmoim.catalog.domain.SubjectType;
import com.duckmoim.catalog.domain.Trust;
import com.duckmoim.catalog.service.EventIngestCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 적재할 행사 한 건 (EV-03 · API-설계 「2-8. 적재 (Ingest)」).
 *
 * <p>필드 이름과 값 집합은 화면 계약 1장을 그대로 따른다. 크롤러 · 프론트 · 백엔드 셋이 같은 필드를 쓰므로 어느 코드도 정본이 될 수 없다.
 *
 * <p><b>필수 판정이 EV-04 의 통과 조건 셋 중 하나다</b> — 「요청에 실린 모든 행의 필수 필드가 하나도 비어 있지 않다」. 그래서 여기서 걸러 400 으로
 * 되돌리는 것이 곧 불통과다. 크롤러가 자기 출력을 자기가 검사하게 두지 않는다.
 *
 * <p>{@code @NotBlank} 를 쓰는 자리와 {@code @NotNull} 을 쓰는 자리가 갈린다 — 빈 문자열도 결측으로 세기로 했으므로 문자열 필수는 전부
 * {@code NotBlank} 다.
 *
 * <p><b>{@code id}(숫자 PK)를 받지 않는다.</b> 경로 변수와 마찬가지로 외부 식별자만 쓴다 (API-설계 「2-3. 행사 (Catalog)」).
 * <b>{@code goods} 도 받지 않는다</b> — 어느 수집원도 굿즈를 주지 않아 전량 빈 배열이다.
 */
public record EventIngestItemRequest(
    @Schema(description = "외부 식별자. upsert 의 멱등성 키", example = "kopis_PF264073")
        @NotBlank(message = "외부 식별자는 필수입니다.")
        @Size(max = 64, message = "외부 식별자는 64자 이하여야 합니다.")
        String externalId,
    @Schema(description = "수집원. 접두어에서 유도하지 않고 크롤러가 명시한다 (D-9)") @NotNull(message = "수집원은 필수입니다.")
        EventSource source,
    @Schema(description = "행사 종류") @NotNull(message = "행사 종류는 필수입니다.") EventKind kind,
    @Schema(description = "대상 종류") @NotNull(message = "대상 종류는 필수입니다.") SubjectType subjectType,
    @Schema(description = "신뢰도. 검증하지 않은 것을 OFFICIAL 로 올리지 않는다") @NotNull(message = "신뢰도는 필수입니다.")
        Trust trust,
    @Schema(description = "정규화된 대상명", example = "에스파")
        @NotBlank(message = "대상명은 필수입니다.")
        @Size(max = 100, message = "대상명은 100자 이하여야 합니다.")
        String subject,
    @Schema(description = "수집원의 원제", nullable = true)
        @Size(max = 200, message = "원제는 200자 이하여야 합니다.")
        String title,
    @Schema(description = "시작일") @NotNull(message = "시작일은 필수입니다.") LocalDate startsOn,
    @Schema(description = "종료일") @NotNull(message = "종료일은 필수입니다.") LocalDate endsOn,
    @Schema(description = "운영 시간 문자열", nullable = true)
        @Size(max = 100, message = "운영 시간은 100자 이하여야 합니다.")
        String openHours,
    @Schema(description = "공연 시작 시각. 콘서트만 갖는다 (EV-10)", nullable = true) LocalTime startsAt,
    @Schema(description = "특전", nullable = true) @Size(max = 500, message = "특전은 500자 이하여야 합니다.")
        String perks,
    @Schema(description = "참여 조건", nullable = true)
        @Size(max = 500, message = "참여 조건은 500자 이하여야 합니다.")
        String conditions,
    @Schema(description = "원문 링크. 화면에 반드시 노출한다")
        @NotBlank(message = "원문 링크는 필수입니다.")
        @Size(max = 500, message = "원문 링크는 500자 이하여야 합니다.")
        String sourceUrl,
    @Schema(description = "리스팅 출처", nullable = true)
        @Size(max = 500, message = "리스팅 출처는 500자 이하여야 합니다.")
        String listingUrl,
    @Schema(description = "예매·사전예약 링크", nullable = true)
        @Size(max = 500, message = "예매 링크는 500자 이하여야 합니다.")
        String reservationUrl,
    @Schema(description = "포스터 주소. 수집원 것을 그대로 참조한다", nullable = true)
        @Size(max = 500, message = "포스터 주소는 500자 이하여야 합니다.")
        String imageUrl,
    @Schema(description = "장소") @NotNull(message = "장소는 필수입니다.") @Valid PlaceRequest place) {

  /** 행사가 열리는 곳 (화면 계약 「{@code Place}」). */
  public record PlaceRequest(
      @Schema(description = "장소명")
          @NotBlank(message = "장소명은 필수입니다.")
          @Size(max = 100, message = "장소명은 100자 이하여야 합니다.")
          String name,
      @Schema(description = "도로명 주소")
          @NotBlank(message = "주소는 필수입니다.")
          @Size(max = 200, message = "주소는 200자 이하여야 합니다.")
          String address,
      @Schema(description = "위도") @NotNull(message = "위도는 필수입니다.") java.math.BigDecimal lat,
      @Schema(description = "경도") @NotNull(message = "경도는 필수입니다.") java.math.BigDecimal lng,
      @Schema(description = "지역 코드. Region.code 와 같은 값이고 소문자다", example = "jamsil")
          @NotBlank(message = "지역 코드는 필수입니다.")
          String district,
      @Schema(description = "장소 종류") @NotNull(message = "장소 종류는 필수입니다.") PlaceKind kind) {}

  EventIngestCommand.Item toItem() {
    return new EventIngestCommand.Item(
        new EventCrawl(
            externalId,
            source,
            kind,
            subjectType,
            trust,
            subject,
            title,
            startsOn,
            endsOn,
            openHours,
            startsAt,
            perks,
            conditions,
            sourceUrl,
            listingUrl,
            reservationUrl,
            imageUrl,
            place.name(),
            place.address(),
            place.lat(),
            place.lng(),
            place.kind()),
        place.district());
  }
}
