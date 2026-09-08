package com.duckmoim.companion.presentation;

import com.duckmoim.companion.domain.MeetPoint;
import java.math.BigDecimal;

/**
 * 만남지점 (PO-03 · I-05).
 *
 * <p>좌표 이름이 행사 {@code Place} 와 같다. 지도가 한 모양만 알면 된다 (API-설계.md 「5. 결정 사항」).
 *
 * <p><b>작성 · 목록 · 상세 셋이 같은 모양을 쓴다.</b> 그래서 {@code CompanionPostResponse} 안에 중첩해 두지 않고 자기 파일로 꺼냈다 —
 * 중첩이면 목록과 상세가 「작성 응답의 일부」를 참조하게 되고, 작성 응답을 고치는 날 세 곳이 함께 움직인다.
 */
public record MeetPointResponse(String place, BigDecimal lat, BigDecimal lng) {

  static MeetPointResponse from(MeetPoint meetPoint) {
    return new MeetPointResponse(meetPoint.getPlace(), meetPoint.getLat(), meetPoint.getLng());
  }
}
