package com.duckmoim.identity.domain;

import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.identity.exception.UserErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 가입 시 입력받는 출생연도 (도메인-모델링.md 「1. 유비쿼터스 언어」).
 *
 * <p><b>만 14세 미만 차단(I-15)의 판정 근거이고, 그것이 이 값 객체가 존재하는 이유다.</b> 판정을 서비스에 두면 프로필 수정 · 관리자 경로처럼 나중에 생길
 * 입구마다 같은 검사가 하나씩 생긴다.
 *
 * <p><b>생일을 받지 않는다.</b> 연도만 아는 채로 만 나이를 정확히 셀 수 없어 {@code 올해 − 출생연도 >= 15} 로 <b>보수적으로</b> 잡았다
 * (I-15). 그래서 경계 연도({@code 올해 − 15})에 태어난 사람은 생일이 지나지 않아 아직 만 14세여도 가입할 수 있다 — 규칙이 그렇게 정해졌다.
 *
 * <p><b>「올해」를 스스로 읽지 않는다.</b> {@code domain} 은 프레임워크에 묶이지 않고, 시각을 안에서 읽으면 테스트가 실행 연도에 끌려간다. 기준은
 * <b>KST</b> 다 (도메인 4장).
 *
 * <p><b>아래쪽 경계는 두지 않았다.</b> 위키가 정하지 않았고 출생연도는 어느 응답에도 나가지 않는다 (API 설계 2-2 의 {@code /users/me} 목록에
 * 없다). 형식 자체가 어긋난 값은 요청 DTO 의 Bean Validation 이 막는다.
 */
@Embeddable
@Getter
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BirthYear {

  /** {@code 올해 − 출생연도} 가 이 값 이상이어야 가입할 수 있다 (I-15). */
  private static final int MINIMUM_YEAR_GAP = 15;

  @Column(name = "birth_year")
  private Integer value;

  private BirthYear(Integer value) {
    this.value = value;
  }

  /**
   * 출생연도를 만든다.
   *
   * @param currentYear KST 기준 올해. 부르는 쪽이 {@code Clock} 으로 읽어 넘긴다
   * @throws BusinessException {@code 올해 − 출생연도 < 15} 이면 {@code USER_UNDER_MINIMUM_AGE} (I-15)
   */
  public static BirthYear of(int value, int currentYear) {
    if (currentYear - value < MINIMUM_YEAR_GAP) {
      throw new BusinessException(UserErrorCode.USER_UNDER_MINIMUM_AGE);
    }

    return new BirthYear(value);
  }
}
