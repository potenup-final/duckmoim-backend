package com.duckmoim.companion.domain;

import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.companion.exception.PostErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 모집글의 정원 (PO-05 · I-03).
 *
 * <p>방장을 포함한 최대 참여 인원이다 (도메인-모델링.md 「1. 유비쿼터스 언어」). <b>표시용이고 상태 전이에 관여하지 않는다</b> — 정원이 찼다고 글이 닫히지
 * 않는다. 신청·수락이 1차 범위에서 빠지면서 셀 인원 자체가 없다.
 *
 * <p><b>「정원 없음」을 값 객체로 만들지 않는다.</b> PO-05 가 선택 입력이라 없는 상태가 정상이고, 그때는 화면이 정원을 아예 표시하지 않는다. 없음을 뜻하는
 * 인스턴스를 따로 두면 「없음인지」를 묻는 분기가 호출부마다 생긴다. 그래서 {@link #of} 가 값이 없으면 {@code null} 을 준다 — Hibernate 도 모든
 * 필드가 {@code null} 인 embeddable 을 {@code null} 로 읽어, 저장과 조회가 같은 모양이 된다.
 *
 * <p>범위는 이중 방어가 있다 — {@code ck_companion_post_capacity} 체크 제약이 V20 에 있다 (I-03).
 */
@Embeddable
@Getter
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Capacity {

  private static final int MIN = 2;
  private static final int MAX = 6;

  @Column(name = "capacity")
  private Integer value;

  private Capacity(Integer value) {
    this.value = value;
  }

  /**
   * 정원을 만든다. <b>값이 없으면 {@code null} 이다</b> — 미입력이어도 작성은 성공한다 (PO-05).
   *
   * @throws BusinessException 값이 있는데 2~6 밖이면 {@code POST_CAPACITY_OUT_OF_RANGE} (I-03)
   */
  public static Capacity of(Integer value) {
    if (value == null) {
      return null;
    }
    if (value < MIN || value > MAX) {
      throw new BusinessException(PostErrorCode.POST_CAPACITY_OUT_OF_RANGE);
    }

    return new Capacity(value);
  }

  /** 저장된 정원을 응답으로 옮길 때 쓴다. 정원이 없으면 이 객체 자체가 없다. */
  public static Integer valueOf(Capacity capacity) {
    return capacity == null ? null : capacity.value;
  }
}
