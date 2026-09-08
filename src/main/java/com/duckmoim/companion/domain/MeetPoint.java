package com.duckmoim.companion.domain;

import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.common.exception.CommonErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 모집글의 만남 지점 (PO-03 · I-05).
 *
 * <p><b>장소명과 좌표를 모두 갖는다.</b> 도메인-모델링.md 「5. 불변식」이 I-05 의 검증 위치를 「생성·수정 시」로 정했고, 셋 중 하나라도 비면 이 객체가
 * 만들어지지 않는다. 핀 없이 올라간 모집글은 지도에 그릴 수 없어 화면 계약이 아예 제출을 막는다.
 *
 * <p><b>필드명은 {@code place} · {@code lat} · {@code lng} 로 확정됐다</b> (2026-09-05. API-설계.md 「5. 결정
 * 사항」). 좌표 이름이 행사 {@code Place} 와 같아져서 지도가 한 모양만 알면 된다.
 *
 * <p>값 객체라 불변이다 (도메인-모델링.md 「4. 엔티티 · 값 객체 · 식별자」). setter 를 두지 않고, 바꿔야 하면 새로 만든다.
 *
 * <p><b>여기가 마지막 방어선이고 첫 방어선이 아니다.</b> 누락은 요청 DTO 의 Bean Validation 이 먼저 400 으로 돌려보낸다 (API-컨벤션.md
 * 「Validation 규칙」). 이중 방어가 DB 의 NOT NULL 이라 이 판정을 지나지 않는 경로는 저장에서 터진다.
 */
@Embeddable
@Getter
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MeetPoint {

  @Column(name = "meet_place", nullable = false, length = 100)
  private String place;

  @Column(name = "meet_lat", nullable = false, precision = 10, scale = 7)
  private BigDecimal lat;

  @Column(name = "meet_lng", nullable = false, precision = 10, scale = 7)
  private BigDecimal lng;

  private MeetPoint(String place, BigDecimal lat, BigDecimal lng) {
    this.place = place;
    this.lat = lat;
    this.lng = lng;
  }

  /**
   * 장소명과 좌표로 만남 지점을 만든다.
   *
   * <p>셋 중 하나라도 없으면 만들지 않는다 (I-05). 좌표만 없는 경우가 실제로 잦다 — 사용자가 장소를 타이핑만 하고 지도에서 찍지 않은 것이다. 그때도 같은 400
   * 이다.
   */
  public static MeetPoint of(String place, BigDecimal lat, BigDecimal lng) {
    if (place == null || place.isBlank() || lat == null || lng == null) {
      throw new BusinessException(CommonErrorCode.INVALID_INPUT);
    }

    return new MeetPoint(place, lat, lng);
  }
}
